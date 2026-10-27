package com.rpgcam.client;

import com.rpgcam.cue.CueRegistry;
import com.rpgcam.cue.CueSpec;
import com.rpgcam.net.ModPayloads;
import com.vjj.client.VjjUiClient;
import com.vjj.net.VjjPayloads;
import com.vjj.store.CameraClip;
import com.vjj.store.CameraKey;
import com.vjj.store.VjjConfigStore;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * 客户端镜头导演：持有当前 cue / 关键帧镜头，逐帧把「样式参数 / 关键帧 + 进度 + 锚点」算成相机位姿，
 * 交给 {@code CameraMixin} 应用到 {@code net.minecraft.client.Camera} 上。
 *
 * <p>两种活动态并存，<b>优先级：关键帧镜头（clip） &gt; 内置样式（cue）</b>：</p>
 * <ul>
 *   <li><b>cue</b>：服务端只发样式 + 时长 + 锚点 + 种子，运动轨迹由 {@code CameraMath} 按样式演算
 *       （13 个内置样式，{@code /cinematic} 与旧功能继续可用）；</li>
 *   <li><b>clip</b>：服务端发整段关键帧镜头 JSON（{@code ClipPlayPayload}），客户端按 tick 在相邻关键帧间
 *       插值（linear / smooth=smoothstep / step），位置与朝向都插值、FOV 也插值；
 *       {@code relative} 模式 = 锚点（目标玩家/实体）眼睛 + 偏移，{@code world} 模式 = 绝对坐标；
 *       首帧之前 / 末帧之后 clamp。</li>
 * </ul>
 *
 * <p>演出期间额外接管两件事：</p>
 * <ul>
 *   <li>临时切换到第三人称（26.3 只在第三人称渲染本地玩家模型，否则环绕镜头里看不到角色），
 *       结束/中断/离开世界时恢复用户原本的相机类型；</li>
 *   <li>HUD 隐藏与黑边/标题由 {@code HudMixin} 负责（它读 {@link #activeSpec()} 的 hideHud；cue 专用）。</li>
 * </ul>
 *
 * <p>日志契约（E2E 依赖，见 docs/VJJ-DESIGN.md §3.1 / §7）：</p>
 * <pre>
 * 旧样式（rpgcam，保持不变）：
 * [RpgCamera] cue-start style=&lt;id&gt; duration=&lt;n&gt; label="..." anchorEntity=&lt;id|-&gt; anchorPos=&lt;x,y,z|-&gt; local=&lt;true|false&gt;
 * [RpgCamera] pose-apply style=&lt;id&gt; t=&lt;0..1&gt; pos=&lt;x,y,z&gt; rot=&lt;yaw,pitch&gt; fov=&lt;f&gt; playerPos=&lt;x,y,z&gt;
 * [RpgCamera] cue-end style=&lt;id&gt; ticks=&lt;n&gt; reason=&lt;finished|stop:...&gt; restored=1 local=&lt;...&gt;
 *
 * 关键帧镜头（VJJ-NEW）：
 * [VjjNew] clip-start clip=&lt;id&gt; duration=&lt;n&gt; keys=&lt;n&gt; force=&lt;b&gt; ...
 * [VjjNew] clip-pose clip=&lt;id&gt; t=&lt;0..1&gt; pos=&lt;x,y,z&gt; rot=&lt;yaw,pitch&gt; fov=&lt;f&gt; playerPos=&lt;x,y,z&gt;
 * [VjjNew] clip-end clip=&lt;id&gt; ticks=&lt;n&gt; reason=&lt;finished|stop|replaced&gt; restored=1
 * </pre>
 */
public final class CameraDirector {
    private static final CameraDirector INSTANCE = new CameraDirector();

    public static CameraDirector get() {
        return INSTANCE;
    }

    /** 一帧的姿态结果。fov&lt;=0 表示沿用原版 FOV。 */
    public record PoseData(String styleId, Vec3 pos, float yaw, float pitch, float fov,
                           float progress, float letterbox, float fadeAlpha, String label,
                           boolean hideHud) {
    }

    private record Active(String styleId, CueSpec spec, int durationTicks, int anchorEntityId,
                          boolean hasAnchorPos, Vec3 anchorPos, String label, int seed,
                          long startTick, boolean local) {
    }

    /** 活动中的关键帧镜头（客户端独立演算，按 duration 自行收尾）。 */
    private record ClipActive(String clipId, CameraClip data, int durationTicks, int anchorEntityId,
                              boolean forceCamera, String label, long startTick) {
    }

    /** 关键帧采样结果（pose 与 FOV 共用同一份插值）。 */
    private record ClipSample(float progress, Vec3 pos, float yaw, float pitch, float fov) {
    }

    private Active active;
    private ClipActive clipActive;
    private PoseData lastPose;
    private long tickCounter;
    private long lastPoseLogTick = Long.MIN_VALUE;
    private long lastClipPoseLogTick = Long.MIN_VALUE;

    /** 演出期间强制第三人称用的状态（保存-恢复）。 */
    private CameraType savedCameraType;
    private boolean cameraTypeForced;

    private CameraDirector() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 状态
    // ─────────────────────────────────────────────────────────────────────

    /** 是否在接管相机（cue 或 clip）。 */
    public boolean isActive() {
        return active != null || clipActive != null;
    }

    /** cue 活动态（旧 13 样式；HUD/autotest 兼容保留语义）。 */
    public boolean isCueActive() {
        return active != null;
    }

    public String activeStyleId() {
        return active == null ? null : active.styleId();
    }

    public CueSpec activeSpec() {
        return active == null ? null : active.spec();
    }

    public int activeDurationTicks() {
        return active == null ? 0 : active.durationTicks();
    }

    public long activeStartTick() {
        return active == null ? 0L : active.startTick();
    }

    public long elapsedTicks() {
        return active == null ? 0L : Math.max(0L, tickCounter - active.startTick());
    }

    public boolean isLocal() {
        return active != null && active.local();
    }

    public PoseData lastPose() {
        return lastPose;
    }

    public long tickCounter() {
        return tickCounter;
    }

    /** 演出中是否临时切了第三人称（诊断/自测用）。 */
    public boolean isCameraTypeForced() {
        return cameraTypeForced;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 状态：关键帧镜头（clip）
    // ─────────────────────────────────────────────────────────────────────

    public boolean isClipActive() {
        return clipActive != null;
    }

    public String activeClipId() {
        return clipActive == null ? null : clipActive.clipId();
    }

    public int clipDurationTicks() {
        return clipActive == null ? 0 : clipActive.durationTicks();
    }

    public long clipElapsedTicks() {
        return clipActive == null ? 0L : Math.max(0L, tickCounter - clipActive.startTick());
    }

    /** 归一化进度 0..1（tick 粒度，不含 partialTick）。 */
    public float clipProgress() {
        ClipActive clip = clipActive;
        if (clip == null) {
            return 0.0F;
        }
        return Mth.clamp(clipElapsedTicks() / (float) Math.max(1, clip.durationTicks()), 0.0F, 1.0F);
    }

    /** 当前是否强制接管相机（force=false 的 clip 只播不接管）。 */
    public boolean isClipForcingCamera() {
        return clipActive != null && clipActive.forceCamera();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 输入：服务端 cue / 本地预览
    // ─────────────────────────────────────────────────────────────────────

    public void receiveCue(ModPayloads.CuePayload payload) {
        CueSpec spec = CueRegistry.get(payload.styleId());
        if (spec == null) {
            com.rpgcam.RpgCamera.LOGGER.warn("[RpgCamera] cue-receive-unknown style={}", payload.styleId());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            com.rpgcam.RpgCamera.LOGGER.warn("[RpgCamera] cue-receive-not-in-world style={}", payload.styleId());
            return;
        }
        if (active != null) {
            finish("replaced");
        }
        active = new Active(spec.id(), spec, payload.durationTicks(), payload.anchorEntityId(),
                payload.hasAnchorPos(), new Vec3(payload.anchorX(), payload.anchorY(), payload.anchorZ()),
                payload.label() == null ? "" : payload.label(), payload.seed(), tickCounter, false);
        lastPose = null;
        lastPoseLogTick = Long.MIN_VALUE;
        forceThirdPerson(mc);
        com.rpgcam.RpgCamera.LOGGER.info(
                "[RpgCamera] cue-start style={} duration={} label=\"{}\" anchorEntity={} anchorPos={} local=false",
                spec.id(), payload.durationTicks(), active.label(),
                payload.anchorEntityId(), payload.hasAnchorPos()
                        ? String.format("%.2f,%.2f,%.2f", payload.anchorX(), payload.anchorY(), payload.anchorZ())
                        : "-");
        CameraAutotest.onCueStart(this);
    }

    public void receiveStop(ModPayloads.StopPayload payload) {
        if (active == null) {
            return;
        }
        boolean matches = payload.styleId() == null || payload.styleId().isEmpty()
                || payload.styleId().equals(active.styleId());
        if (!matches) {
            return;
        }
        finish("stop:" + (payload.reason() == null || payload.reason().isEmpty() ? "server" : payload.reason()));
    }

    /** 客户端本地预览（键位 / autotest 用，不经过服务端）。 */
    public void playLocal(String styleId, int durationTicks) {
        CueSpec spec = CueRegistry.get(styleId);
        if (spec == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (active != null) {
            finish("replaced");
        }
        int duration = durationTicks > 0 ? durationTicks : spec.durationTicks();
        active = new Active(spec.id(), spec, duration, -1, false, Vec3.ZERO, "", 1,
                tickCounter, true);
        lastPose = null;
        lastPoseLogTick = Long.MIN_VALUE;
        forceThirdPerson(mc);
        com.rpgcam.RpgCamera.LOGGER.info(
                "[RpgCamera] cue-start style={} duration={} label=\"\" anchorEntity=- anchorPos=- local=true",
                spec.id(), duration);
        CameraAutotest.onCueStart(this);
    }

    public void stopLocal() {
        if (active != null) {
            finish("local-stop");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 输入：服务端关键帧镜头（VJJ-NEW）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 服务端下发“播放可编辑镜头”：解析整段关键帧，开始本地演算。
     *
     * <p>{@code forceCamera=true} 时即使没有关键帧也接管相机（关键帧镜头本身就是接管）；
     * {@code forceCamera=false} 时只记录活动态、不接管（用于只广播演出、保留玩家视角的场景）。</p>
     */
    public void receiveClip(VjjPayloads.ClipPlayPayload payload) {
        if (payload == null) {
            return;
        }
        CameraClip data;
        try {
            data = VjjConfigStore.parseClip(payload.clipJson());
        } catch (RuntimeException exception) {
            // 网络包内容异常不能把客户端主线程打崩：记日志直接放弃
            VjjUiClient.LOGGER.warn("[VjjNew] clip-start clip=- result=-1 reason=bad-json error={}",
                    exception.toString());
            return;
        }
        if (data == null) {
            VjjUiClient.LOGGER.info("[VjjNew] clip-start clip=- duration=0 keys=0 force=false result=-1 "
                    + "reason=bad-json source={}", payload.source());
            return;
        }
        data.ensureUsable();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            VjjUiClient.LOGGER.info("[VjjNew] clip-start clip={} duration=0 keys=0 force=false result=-1 "
                    + "reason=not-in-world", data.id);
            return;
        }
        if (clipActive != null) {
            finishClip("replaced");
        }
        int duration = payload.durationTicks() > 0 ? payload.durationTicks() : Math.max(1, data.durationTicks);
        String clipId = data.id == null || data.id.isBlank()
                ? (payload.label() == null || payload.label().isBlank() ? "unnamed" : payload.label())
                : data.id;
        String label = data.name == null || data.name.isBlank() ? clipId : data.name;
        int keys = data.keys == null ? 0 : data.keys.size();
        clipActive = new ClipActive(clipId, data, duration, payload.anchorEntityId(),
                payload.forceCamera(), label, tickCounter);
        lastClipPoseLogTick = Long.MIN_VALUE;
        lastPose = null;
        if (payload.forceCamera()) {
            forceThirdPerson(mc);
        }
        VjjUiClient.LOGGER.info("[VjjNew] clip-start clip={} duration={} keys={} force={} anchor={} source={} mode={}",
                clipId, duration, keys, payload.forceCamera(), payload.anchorEntityId(),
                payload.source(), data.mode);
        // 兼容 docs 里 style=<clipId> 的写法（E2E 两种 grep 都能命中）
        VjjUiClient.LOGGER.info("[VjjNew] clip-start style={} duration={} keys={} force={}",
                clipId, duration, keys, payload.forceCamera());
    }

    /** 服务端下发停止（兜底；客户端通常已按时长自己结束）。 */
    public void receiveClipStop(String reason) {
        String effective = switch (reason == null ? "" : reason) {
            case "finished", "stop", "replaced" -> reason;
            case "" -> "stop";
            default -> "stop";
        };
        finishClip(effective);
    }

    /** 掉线/重置时清空关键帧镜头（不碰 Minecraft 全局状态，交给下一次客户端 tick 的兜底恢复）。 */
    public void resetClip() {
        ClipActive finished = clipActive;
        if (finished == null) {
            return;
        }
        clipActive = null;
        lastClipPoseLogTick = Long.MIN_VALUE;
        lastPose = null;
        VjjUiClient.LOGGER.info("[VjjNew] clip-end clip={} ticks={} reason=stop restored=1",
                finished.clipId(), Math.max(0L, tickCounter - finished.startTick()));
    }

    /** 当前关键帧镜头的关键帧数（诊断用）。 */
    public int clipKeyCount() {
        ClipActive clip = clipActive;
        return clip == null || clip.data() == null || clip.data().keys == null ? 0 : clip.data().keys.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 每客户端 tick：推进计时 / 自动收尾 / 兜底恢复
    // ─────────────────────────────────────────────────────────────────────

    public void onClientTick(Minecraft mc) {
        tickCounter++;
        if (clipActive != null && (mc.player == null || mc.level == null)) {
            finishClip("stop");
        } else if (clipActive != null && clipElapsedTicks() >= clipActive.durationTicks()) {
            finishClip("finished");
        }
        if (active != null && elapsedTicks() >= active.durationTicks()) {
            finish("finished");
        }
        // 极端情况（掉线/被其它界面顶掉）下也要把相机类型还回去
        if (cameraTypeForced && (mc.player == null || mc.level == null)) {
            restoreCameraType(mc);
        }
        CameraAutotest.tick(mc, this);
    }

    private void finish(String reason) {
        Active finished = active;
        if (finished == null) {
            return;
        }
        active = null;
        lastPose = null;
        Minecraft mc = Minecraft.getInstance();
        if (clipActive == null) {
            restoreCameraType(mc);
        }
        long ticks = Math.max(0L, tickCounter - finished.startTick());
        com.rpgcam.RpgCamera.LOGGER.info(
                "[RpgCamera] cue-end style={} ticks={} reason={} restored=1 local={}",
                finished.styleId(), ticks, reason, finished.local());
        if (!finished.local()) {
            ClientNetworking.sendDone(finished.styleId(), (int) Math.min(ticks, Integer.MAX_VALUE),
                    !"finished".equals(reason));
        }
        CameraAutotest.onCueEnd(this);
    }

    private void finishClip(String reason) {
        ClipActive finished = clipActive;
        if (finished == null) {
            return;
        }
        clipActive = null;
        lastClipPoseLogTick = Long.MIN_VALUE;
        lastPose = null;
        Minecraft mc = Minecraft.getInstance();
        if (active == null) {
            restoreCameraType(mc);
        }
        long ticks = Math.max(0L, tickCounter - finished.startTick());
        VjjUiClient.LOGGER.info("[VjjNew] clip-end clip={} ticks={} reason={} restored=1",
                finished.clipId(), ticks, reason);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第三人称接管（让本地玩家模型在过场中可见）
    // ─────────────────────────────────────────────────────────────────────

    private void forceThirdPerson(Minecraft mc) {
        if (cameraTypeForced) {
            return;
        }
        savedCameraType = mc.options.getCameraType();
        cameraTypeForced = true;
        if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        com.rpgcam.RpgCamera.LOGGER.info("[RpgCamera] camera-type saved={} forced=third_person_back",
                savedCameraType);
    }

    private void restoreCameraType(Minecraft mc) {
        if (!cameraTypeForced) {
            return;
        }
        cameraTypeForced = false;
        if (savedCameraType != null && mc.options.getCameraType() != savedCameraType) {
            mc.options.setCameraType(savedCameraType);
        }
        com.rpgcam.RpgCamera.LOGGER.info("[RpgCamera] camera-type restored={}", savedCameraType);
        savedCameraType = null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 逐帧：位姿演算（clip 优先于 cue）
    // ─────────────────────────────────────────────────────────────────────

    /** 由 CameraMixin 在每帧调用；返回 null 表示这一帧不接管相机。 */
    public PoseData poseFor(float partialTick) {
        if (clipActive != null && clipActive.forceCamera()) {
            return clipPose(partialTick);
        }
        return cuePose(partialTick);
    }

    private PoseData cuePose(float partialTick) {
        Active a = active;
        if (a == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        CueSpec spec = a.spec();
        float rawProgress = (elapsedTicks() + partialTick) / Math.max(1.0F, (float) a.durationTicks());
        float t = Mth.clamp(rawProgress, 0.0F, 1.0F);
        double e = CameraMath.ease(spec.motion(), t);

        // 锚点（眼睛位置）
        Entity anchorEntity = null;
        if (a.anchorEntityId() >= 0) {
            anchorEntity = mc.level.getEntity(a.anchorEntityId());
        }
        Vec3 anchorEye;
        if (anchorEntity != null) {
            anchorEye = anchorEntity.getEyePosition(partialTick);
        } else if (a.hasAnchorPos()) {
            anchorEye = a.anchorPos().add(0.0, 1.62, 0.0);
        } else {
            anchorEye = player.getEyePosition(partialTick);
        }

        double baseYaw = anchorEntity != null
                ? anchorEntity.getYRot()
                : player.getViewYRot(partialTick);
        Vec3 velocity = player.getDeltaMovement();
        double walkAmount = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

        Vec3 desired = CameraMath.position(spec, t, e, baseYaw, anchorEye, a.seed(), walkAmount);
        Vec3 pos = CameraMath.clampToWorld(mc.level, anchorEye, desired);

        // 注视点
        Vec3 lookAt = switch (spec.look()) {
            case ANCHOR_EYE -> anchorEye;
            case ANCHOR_CENTER -> anchorEntity != null
                    ? anchorEntity.position().add(0.0, anchorEntity.getBbHeight() * 0.5, 0.0)
                    : player.position().add(0.0, 1.0, 0.0);
            case PLAYER_EYE -> player.getEyePosition(partialTick);
            case PLAYER_FEET -> player.position();
            case KEEP -> null;
        };

        float yaw;
        float pitch;
        if (lookAt != null) {
            yaw = CameraMath.yawTowards(pos, lookAt);
            pitch = CameraMath.pitchTowards(pos, lookAt);
        } else {
            yaw = player.getViewYRot(partialTick);
            pitch = player.getViewXRot(partialTick);
        }

        float fov = spec.changesFov() ? (float) CameraMath.lerp(e, spec.fovStart(), spec.fovEnd()) : -1.0F;
        float letterbox = spec.letterbox() * CameraMath.envelope(t, a.durationTicks(), 6);
        float fade = CameraMath.fadeAlpha(t, a.durationTicks(), spec.fadeInTicks(), spec.fadeOutTicks());

        lastPose = new PoseData(a.styleId(), pos, yaw, pitch, fov, t, letterbox, fade,
                a.label(), spec.hideHud());
        return lastPose;
    }

    private PoseData clipPose(float partialTick) {
        ClipActive clip = clipActive;
        if (clip == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }
        ClipSample sample = sampleClip(clip, partialTick);
        if (sample == null) {
            return null;
        }
        logClipPose(clip, sample);
        lastPose = new PoseData(clip.clipId(), sample.pos(), sample.yaw(), sample.pitch(), sample.fov(),
                sample.progress(), 0.0F, 0.0F, clip.label(), false);
        return lastPose;
    }

    /**
     * 关键帧插值：按 tick 找相邻两帧，位置/朝向/FOV 一起插值；首帧前 / 末帧后 clamp。
     */
    private ClipSample sampleClip(ClipActive clipActiveSnapshot, float partialTick) {
        CameraClip data = clipActiveSnapshot.data();
        List<CameraKey> keys = data == null || data.keys == null ? List.of() : data.keys;
        if (keys.isEmpty()) {
            return null;
        }
        double timeTicks = Math.max(0.0, (double) (tickCounter - clipActiveSnapshot.startTick())) + partialTick;
        float duration = Math.max(1.0F, (float) clipActiveSnapshot.durationTicks());
        float progress = Mth.clamp((float) (timeTicks / duration), 0.0F, 1.0F);

        CameraKey previous;
        CameraKey next;
        double segment = 0.0;
        if (keys.size() == 1) {
            previous = keys.get(0);
            next = previous;
        } else if (timeTicks <= keys.get(0).tick) {
            previous = keys.get(0);
            next = previous;
        } else if (timeTicks >= keys.get(keys.size() - 1).tick) {
            previous = keys.get(keys.size() - 1);
            next = previous;
        } else {
            int index = keys.size() - 2;
            for (int i = 0; i < keys.size() - 1; i++) {
                if (timeTicks >= keys.get(i).tick && timeTicks < keys.get(i + 1).tick) {
                    index = i;
                    break;
                }
            }
            previous = keys.get(index);
            next = keys.get(index + 1);
            int span = Math.max(1, next.tick - previous.tick);
            segment = Mth.clamp((timeTicks - previous.tick) / (double) span, 0.0, 1.0);
        }

        double eased = ease(previous.easeOrDefault(), segment);
        double x = lerp(eased, previous.x, next.x);
        double y = lerp(eased, previous.y, next.y);
        double z = lerp(eased, previous.z, next.z);

        Minecraft mc = Minecraft.getInstance();
        Entity anchor = null;
        if (clipActiveSnapshot.anchorEntityId() >= 0 && mc.level != null) {
            anchor = mc.level.getEntity(clipActiveSnapshot.anchorEntityId());
        }
        Vec3 anchorEye;
        if (anchor != null) {
            anchorEye = anchor.getEyePosition(partialTick);
        } else if (mc.player != null) {
            anchorEye = mc.player.getEyePosition(partialTick);
        } else {
            anchorEye = Vec3.ZERO;
        }

        Vec3 pos = data.isRelative() ? anchorEye.add(x, y, z) : new Vec3(x, y, z);
        float yaw = lerpDegrees(previous.yaw, next.yaw, eased);
        float pitch = (float) lerp(eased, previous.pitch, next.pitch);
        float fovStart = previous.fov > 0.0F ? previous.fov : 70.0F;
        float fovEnd = next.fov > 0.0F ? next.fov : 70.0F;
        float fov = (float) lerp(eased, fovStart, fovEnd);
        return new ClipSample(progress, pos, yaw, pitch, fov);
    }

    /** 关键帧缓动：linear / smooth(=smoothstep) / step（保持前一帧到下一帧）。 */
    private static double ease(String ease, double t) {
        String value = ease == null ? "smooth" : ease.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "linear" -> t;
            case "step" -> t >= 1.0 ? 1.0 : 0.0;
            default -> t * t * (3.0 - 2.0 * t);
        };
    }

    private static double lerp(double delta, double start, double end) {
        return start + (end - start) * delta;
    }

    private static float lerpDegrees(float start, float end, double delta) {
        return start + Mth.wrapDegrees(end - start) * (float) delta;
    }

    /** 由 CameraMixin 调用：按当前样式/关键帧缩放或覆盖原版 FOV（在投影矩阵生成前生效）。 */
    public float modifyFov(float vanillaFov, float partialTick) {
        if (clipActive != null && clipActive.forceCamera()) {
            ClipSample sample = sampleClip(clipActive, partialTick);
            if (sample != null) {
                return sample.fov();
            }
        }
        Active a = active;
        if (a == null || !a.spec().changesFov()) {
            return vanillaFov;
        }
        float rawProgress = (elapsedTicks() + partialTick) / Math.max(1.0F, (float) a.durationTicks());
        float t = Mth.clamp(rawProgress, 0.0F, 1.0F);
        double e = CameraMath.ease(a.spec().motion(), t);
        return (float) CameraMath.lerp(e, a.spec().fovStart(), a.spec().fovEnd());
    }

    /** 每帧应用后记录一次采样（E2E 证据；同 tick 多帧只记第一条，每 10 tick 一条）。 */
    public void onPoseApplied(PoseData pose) {
        if (pose == null) {
            return;
        }
        if (clipActive != null) {
            // 关键帧镜头在 poseFor 里打自己的 clip-pose，避免两套日志互相干扰
            return;
        }
        long tick = tickCounter;
        boolean due = lastPoseLogTick == Long.MIN_VALUE
                || (tick != lastPoseLogTick && (tick % 10 == 0 || pose.progress() > 0.97F));
        if (!due) {
            return;
        }
        lastPoseLogTick = tick;
        Minecraft mc = Minecraft.getInstance();
        Vec3 playerPos = mc.player == null ? Vec3.ZERO : mc.player.position();
        com.rpgcam.RpgCamera.LOGGER.info(
                "[RpgCamera] pose-apply style={} t={} pos={} rot={} fov={} playerPos={}",
                pose.styleId(),
                String.format("%.3f", pose.progress()),
                String.format("%.2f,%.2f,%.2f", pose.pos().x, pose.pos().y, pose.pos().z),
                String.format("%.2f,%.2f", pose.yaw(), pose.pitch()),
                pose.fov() < 0 ? "vanilla" : String.format("%.2f", pose.fov()),
                String.format("%.2f,%.2f,%.2f", playerPos.x, playerPos.y, playerPos.z));
    }

    /** 关键帧镜头采样日志（首帧必打，之后每 10 tick 一条）。 */
    private void logClipPose(ClipActive clip, ClipSample sample) {
        long tick = tickCounter;
        long elapsed = Math.max(0L, tick - clip.startTick());
        boolean first = lastClipPoseLogTick == Long.MIN_VALUE;
        boolean due = first || (tick != lastClipPoseLogTick && elapsed % 10 == 0);
        if (!due) {
            return;
        }
        lastClipPoseLogTick = tick;
        Minecraft mc = Minecraft.getInstance();
        Vec3 playerPos = mc.player == null ? Vec3.ZERO : mc.player.position();
        VjjUiClient.LOGGER.info("[VjjNew] clip-pose clip={} t={} pos={} rot={} fov={} playerPos={}",
                clip.clipId(),
                String.format(Locale.ROOT, "%.3f", sample.progress()),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", sample.pos().x, sample.pos().y, sample.pos().z),
                String.format(Locale.ROOT, "%.2f,%.2f", sample.yaw(), sample.pitch()),
                String.format(Locale.ROOT, "%.2f", sample.fov()),
                String.format(Locale.ROOT, "%.2f,%.2f,%.2f", playerPos.x, playerPos.y, playerPos.z));
    }
}
