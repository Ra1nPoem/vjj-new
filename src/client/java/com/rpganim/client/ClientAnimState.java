package com.rpganim.client;

import com.rpganim.anim.AnimContent;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.anim.AnimSampler;
import com.rpganim.anim.BoneOffsets;
import com.rpganim.net.ModPayloads;
import com.vjj.client.state.VjjEditorState;
import com.vjj.store.VjjSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import org.joml.Vector3f;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端动作状态机：内容同步 + 按实体 id 的本地动作时钟 + 采样结果应用到玩家模型骨骼。
 *
 * <p>时钟语义（设计文档 §5.5）：收到 {@code PlayPayload} 的那一客户端 tick 记为 t=0，
 * 之后每客户端 tick +1；非循环动作到 {@code durationTicks} 自动结束并回调 {@code DonePayload}，
 * 循环动作等 {@code StopPayload}（收到后按 {@code blendOutTicks} 渐出再结束）。
 * 渲染时用 {@code 时钟 + partialTick} 采样，20Hz 的时钟也能在 60fps 下平滑插值。
 *
 * <p>所有状态只由客户端主线程改动（网络回调经 {@code context.client().execute} 切线程），
 * 渲染线程即主线程，因此 mixin 读取时不需要额外同步。
 */
public final class ClientAnimState {
    /** anim-apply 日志节流：普通情况下每个实体/动作每 8 tick 打一行（t=0 必打）。 */
    private static final int APPLY_LOG_INTERVAL = 8;
    /**
     * 动作起始的前 8 tick 每 tick 都打 anim-apply：E2E 的「日志轮询」在读到第一行就返回，
     * 只有前几 tick 连续有行，才能在同一窗口里看到 t=0/1/2… 多个值（证明是插值而非冻结）。
     */
    private static final int APPLY_LOG_EARLY_TICKS = 8;
    /** `[VjjNew] anim-opt cache=hit|miss` 日志条数上限（每个动作，只证明优化存在，不刷屏）。 */
    private static final int OPT_LOG_LIMIT = 24;
    private static final float MAX_DURATION_TICKS = 6000.0F;
    private static final float[] ZERO_ROTATION = new float[] {0.0F, 0.0F, 0.0F};
    /** anim-apply 日志里优先展示的骨骼（内容里最常见、肉眼最容易验证的骨骼）。 */
    private static final List<String> LOG_BONE_ORDER = List.of("right_arm", "left_arm", "body", "head");

    /** 一个实体上正在播放的本地动作。 */
    public static final class ActiveAnim {
        public final int entityId;
        public final String animId;
        public final int durationTicks;
        public final boolean loop;
        public final String source;
        /**
         * 外部动作（VJJ-NEW 假人）：由 {@code com.vjj.client.dummy.VjjDummyClient} 驱动，
         * 不走玩家动作的 C2S Done 回执、也不打 {@code [RpgAnim] anim-end} 干扰玩家动画断言。
         */
        public final boolean external;
        /** 已流逝的客户端 tick（收到 play 的 tick 为 0，之后每 tick +1）。 */
        public int ageTicks;
        /** 内容定义（sync 尚未到达时为 null，HUD 退回显示 animId）。 */
        public AnimDefinition def;
        /** 循环动作收到 stop 后进入渐出阶段。 */
        public boolean stopping;
        public int stopTicks;
        public String stopReason = "stop";
        int lastApplyLogTick = Integer.MIN_VALUE;
        /**
         * 外部（EMF）管线按 tick 的采样缓存：同一 entity/anim/tick 只 sample 一次，
         * 同一 tick 的多帧渲染（EMF 一帧内会多次把 pose 应用回模型）直接复用。
         */
        Map<String, BoneOffsets> sampleCache;
        int sampleCacheTick = Integer.MIN_VALUE;
        int sampleCacheWeightKey = Integer.MIN_VALUE;
        /** anim-opt 日志条数上限（只在前几 tick 打，避免刷屏）。 */
        int optLogCount;

        ActiveAnim(int entityId, String animId, int durationTicks, boolean loop, String source) {
            this(entityId, animId, durationTicks, loop, source, false);
        }

        ActiveAnim(int entityId, String animId, int durationTicks, boolean loop, String source,
                   boolean external) {
            this.entityId = entityId;
            this.animId = animId;
            this.durationTicks = durationTicks;
            this.loop = loop;
            this.source = source;
            this.external = external;
        }

        public boolean fading() {
            return stopping;
        }
    }

    /** 服务端下发的过场（HUD 标题 + 剩余进度）。 */
    public static final class ActiveCutscene {
        public final String cutsceneId;
        public final String titleKey;
        public final int durationTicks;
        public final String cameraStyle;
        public int ageTicks;

        ActiveCutscene(String cutsceneId, String titleKey, int durationTicks, String cameraStyle) {
            this.cutsceneId = cutsceneId;
            this.titleKey = titleKey;
            this.durationTicks = durationTicks;
            this.cameraStyle = cameraStyle;
        }
    }

    private static final Map<Integer, ActiveAnim> ACTIVE = new LinkedHashMap<>();
    private static AnimContent content;
    private static List<String> emoteIds = List.of();
    private static ActiveCutscene cutscene;
    private static boolean noContentWarned;

    private ClientAnimState() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 网络接收（主线程）
    // ─────────────────────────────────────────────────────────────────────

    /** SyncPayload：解析整包 content.json（与服务端同一套解析器），失败只记日志不崩。 */
    public static void receiveSync(ModPayloads.SyncPayload payload) {
        String json = payload.json() == null ? "" : payload.json();
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        AnimContent parsed;
        try {
            parsed = AnimContent.parse(json);
        } catch (RuntimeException exception) {
            RpgAnimClient.LOGGER.error("[RpgAnim] sync parse failed animations={} cutscenes={} bytes={} error={}",
                    payload.animations(), payload.cutscenes(), bytes, exception.getMessage());
            return;
        }
        content = parsed;
        List<String> emotes = new ArrayList<>();
        for (AnimDefinition definition : parsed.animations()) {
            if (definition.isEmote()) {
                emotes.add(definition.id());
            }
        }
        emoteIds = List.copyOf(emotes);
        noContentWarned = false;
        for (ActiveAnim active : ACTIVE.values()) {
            active.def = withLoop(parsed.animation(active.animId), active.loop);
        }
        RpgAnimClient.LOGGER.info("[RpgAnim] sync received animations={} cutscenes={} bytes={}",
                payload.animations(), payload.cutscenes(), bytes);
        if (payload.animations() != parsed.animationCount()) {
            RpgAnimClient.LOGGER.warn("[RpgAnim] sync count mismatch declared={} parsed={}",
                    payload.animations(), parsed.animationCount());
        }
    }

    /** PlayPayload：开始/替换一个实体的动作（旧动作记 reason=replaced）。 */
    public static void receivePlay(ModPayloads.PlayPayload payload) {
        int entityId = payload.entityId();
        String animId = payload.animId() == null ? "" : payload.animId();
        if (entityId <= 0 || animId.isBlank()) {
            RpgAnimClient.LOGGER.warn("[RpgAnim] anim-play ignored entity={} anim={}", entityId, animId);
            return;
        }
        if (content == null) {
            warnNoContent();
        }
        AnimDefinition def = content == null ? null : content.animation(animId);
        ActiveAnim previous = ACTIVE.remove(entityId);
        if (previous != null) {
            reportEnd(previous, "replaced", true);
        }
        int duration = payload.durationTicks();
        if (duration <= 0) {
            duration = def != null ? def.durationTicks() : 40;
        }
        int clamped = (int) Math.max(1.0F, Math.min(duration, MAX_DURATION_TICKS));
        ActiveAnim active = new ActiveAnim(entityId, animId, clamped, payload.loop(),
                payload.source() == null ? "" : payload.source());
        active.def = withLoop(def, payload.loop());
        ACTIVE.put(entityId, active);
        RpgAnimClient.LOGGER.info("[RpgAnim] anim-play entity={} anim={} duration={} loop={} source={}",
                entityId, animId, clamped, active.loop, active.source);
        if (def == null) {
            RpgAnimClient.LOGGER.warn("[RpgAnim] anim-unknown entity={} anim={} (content missing or id not in sync)",
                    entityId, animId);
        }
        AnimAutotest.onActionStart(active);
    }

    /** StopPayload：停止动作；循环动作按 blendOutTicks 渐出（避免姿势硬切）。 */
    public static void receiveStop(ModPayloads.StopPayload payload) {
        ActiveAnim active = ACTIVE.get(payload.entityId());
        if (active == null) {
            return;
        }
        String reason = payload.reason() == null || payload.reason().isBlank() ? "stop" : payload.reason();
        if (active.def != null && active.def.loop()) {
            active.stopping = true;
            active.stopTicks = 0;
            active.stopReason = reason;
            return;
        }
        ACTIVE.remove(payload.entityId());
        reportEnd(active, reason, true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 外部/假人动作入口（VJJ-NEW 契约：只做新增，不影响原玩家动作逻辑）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 开始/重启一个「外部」动作时钟（目前唯一调用方是 VJJ-NEW 的玩家假人渲染）。
     *
     * <p>与 {@link #receivePlay} 的差别：</p>
     * <ul>
     *   <li>不要求网络包：由假人实体同步数据（animId + animSeq）驱动，调用方按 seq 变化重调；</li>
     *   <li>到期自动移除时**不**发 C2S {@code DonePayload}、**不**打 {@code [RpgAnim] anim-end}
     *       （避免污染玩家动作的 E2E 断言），改打 {@code [VjjNew] dummy-anim-end}；</li>
     *   <li>{@code durationTicks <= 0} 用内容定义里的默认时长。</li>
     * </ul>
     *
     * @param entityId      实体 id（假人的服务端 entity id；{@code PlayerModelMixin} 按它叠加骨骼）
     * @param animId        动作 id（必须出现在已 sync 的内容里）
     * @param durationTicks 覆盖时长；{@code <=0} 用内容默认
     * @param loop          是否循环
     * @param source        来源标记（固定传 {@code "dummy"}）
     * @return 实际使用的时长（tick）；内容未 sync / 没有该动作时返回 0（调用方退化为静止）
     */
    public static int startExternalAnim(int entityId, String animId, int durationTicks, boolean loop,
                                        String source) {
        if (entityId <= 0 || animId == null || animId.isBlank()) {
            return 0;
        }
        if (content == null) {
            return 0;
        }
        AnimDefinition def = content.animation(animId);
        if (def == null) {
            return 0;
        }
        ActiveAnim previous = ACTIVE.remove(entityId);
        if (previous != null) {
            reportEnd(previous, "replaced", true);
        }
        int duration = durationTicks > 0 ? durationTicks : def.durationTicks();
        int clamped = (int) Math.max(1.0F, Math.min(duration, MAX_DURATION_TICKS));
        ActiveAnim active = new ActiveAnim(entityId, animId, clamped, loop,
                source == null || source.isBlank() ? "dummy" : source, true);
        active.def = withLoop(def, loop);
        ACTIVE.put(entityId, active);
        return clamped;
    }

    /** 停止一个外部动作（假人实体消失/动作清空时调用）；普通玩家动作不受影响。 */
    public static boolean stopExternalAnim(int entityId, String reason) {
        ActiveAnim active = ACTIVE.get(entityId);
        if (active == null || !active.external) {
            return false;
        }
        ACTIVE.remove(entityId);
        reportEnd(active, reason == null || reason.isBlank() ? "stop" : reason, true);
        return true;
    }

    /** 该实体当前是否由外部（假人）动作驱动（诊断/autotest 用）。 */
    public static boolean isExternalAnim(int entityId) {
        ActiveAnim active = ACTIVE.get(entityId);
        return active != null && active.external;
    }

    /**
     * 以 {@code PlayPayload.loop} 为准改写定义里的 loop 标记（过场 step 的 loop 可能覆盖动作自身设置）。
     * 采样器按定义里的 loop 决定时间轴取模/钳制，不改写会出现「要求循环但姿态冻结在末帧」。
     */
    private static AnimDefinition withLoop(AnimDefinition def, boolean loop) {
        if (def == null || def.loop() == loop) {
            return def;
        }
        return new AnimDefinition(def.id(), def.nameKey(), def.category(), def.durationTicks(), loop,
                def.priority(), def.blendInTicks(), def.blendOutTicks(), def.rig(), def.bones());
    }

    /** CutscenePayload：过场 HUD/autotest（starting=true 开始，false 结束）。 */
    public static void receiveCutscene(ModPayloads.CutscenePayload payload) {
        String cutsceneId = payload.cutsceneId() == null ? "" : payload.cutsceneId();
        if (payload.starting()) {
            if (cutscene != null) {
                RpgAnimClient.LOGGER.info("[RpgAnim] cutscene-end id={} reason=replaced", cutscene.cutsceneId);
            }
            String titleKey = payload.titleKey() == null || payload.titleKey().isBlank()
                    ? "cutscene.rpganim." + cutsceneId
                    : payload.titleKey();
            cutscene = new ActiveCutscene(cutsceneId, titleKey,
                    (int) Math.max(1.0F, Math.min(payload.durationTicks(), MAX_DURATION_TICKS)),
                    payload.cameraStyle() == null ? "" : payload.cameraStyle());
            RpgAnimClient.LOGGER.info("[RpgAnim] cutscene-start id={} duration={} title={}",
                    cutscene.cutsceneId, cutscene.durationTicks, cutscene.titleKey);
            AnimAutotest.onCutsceneStart(cutscene);
        } else {
            if (cutscene == null) {
                return;
            }
            String endedId = cutscene.cutsceneId;
            cutscene = null;
            RpgAnimClient.LOGGER.info("[RpgAnim] cutscene-end id={} reason=finished", endedId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 客户端 tick：推进时钟、到期收尾
    // ─────────────────────────────────────────────────────────────────────

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (!ACTIVE.isEmpty() || cutscene != null) {
                reset("world-leave");
            }
            return;
        }
        Iterator<Map.Entry<Integer, ActiveAnim>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveAnim active = iterator.next().getValue();
            active.ageTicks++;
            if (active.stopping) {
                active.stopTicks++;
                int fadeTicks = active.def == null ? 1 : Math.max(1, active.def.blendOutTicks());
                if (active.stopTicks >= fadeTicks) {
                    iterator.remove();
                    reportEnd(active, active.stopReason, true);
                }
                continue;
            }
            if (!active.loop && active.ageTicks >= active.durationTicks) {
                iterator.remove();
                reportEnd(active, "expired", false);
            }
        }
        if (cutscene != null) {
            cutscene.ageTicks++;
            if (cutscene.ageTicks >= cutscene.durationTicks) {
                // 客户端本地时钟到点即视为过场正常结束（与服务端 ending 包的 reason=finished 统一，
                // 避免本地时钟先到点、服务端包后到的竞态导致 reason 不一致；权威 reason 在服务端日志）
                RpgAnimClient.LOGGER.info("[RpgAnim] cutscene-end id={} reason=finished", cutscene.cutsceneId);
                cutscene = null;
            }
        }
        logFirstPersonApply(mc);
    }

    /**
     * 第一人称时本地玩家模型不渲染（mixin 不会被调用），
     * 这里用同一套采样器补 anim-apply 日志，保证第一人称下也有骨骼被驱动的取证。
     * 第三人称下由 {@code PlayerModelMixin} 真实采样后打日志，两条路径共用节流，不会重复。
     */
    private static void logFirstPersonApply(Minecraft mc) {
        if (mc.player == null || mc.options == null || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        ActiveAnim active = ACTIVE.get(mc.player.getId());
        if (active == null || active.def == null) {
            return;
        }
        float elapsed = active.ageTicks;
        float weight = currentWeight(active, elapsed);
        Map<String, BoneOffsets> sampled = AnimSampler.sample(active.def, elapsed, weight);
        if (!sampled.isEmpty()) {
            logApply(active, elapsed, weight, sampled);
        }
    }

    /** 离开世界/掉线：清空本地动作状态（服务端已不可达，不回执）。 */
    public static void reset(String reason) {
        int cleared = ACTIVE.size();
        ACTIVE.clear();
        cutscene = null;
        if (cleared > 0) {
            RpgAnimClient.LOGGER.info("[RpgAnim] client state cleared animations={} reason={}", cleared, reason);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 渲染注入（PlayerModelMixin / VJJ-EMF 兼容桥调用）：采样 → 叠加到 ModelPart
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 把一个实体的动作偏移叠加到玩家模型骨骼上（原生 mixin 管线；假人渲染也走这里）。
     *
     * <p>行为与 1.0.0 完全一致：每帧用 {@code ageTicks + partialTick} 采样（60fps 平滑插值），
     * additive 叠加，日志格式不变。</p>
     */
    public static void applyToPlayerModel(int entityId, PlayerModel model) {
        if (entityId <= 0) {
            return;
        }
        ActiveAnim active = ACTIVE.get(entityId);
        if (active == null || active.def == null) {
            return;
        }
        applyActive(active, model, 1.0F, false, true, active.external ? "dummy" : "mixin", false);
    }

    /**
     * 外部动画管线（EMF + Touch'n Motion + Fresh Animations 环境）的叠加入口。
     *
     * <p>与 {@link #applyToPlayerModel} 的差别：</p>
     * <ul>
     *   <li>调用方在 EMF 的 {@code onBipedPoseCopyEnd}（EMF 已把 biped pose 应用回模型之后）调用，
     *       因此这里是**最后一层**叠加 → VJJ 动作优先可见；</li>
     *   <li>{@code weightScale} 再乘 {@code settings.overlayWeight}（GUI 可调 0.2–1.0）；</li>
     *   <li>{@code settings.externalPosePriority=true}（默认）时先把 VJJ 动作覆盖到的骨骼
     *       xRot/yRot/zRot 归零再叠加 → VJJ 姿态覆盖 EMF/T&amp;M 的同类骨骼姿态；关掉则纯 additive；</li>
     *   <li>采样按 tick 缓存：同一 entity/anim/tick 在 60fps 下渲染多帧只 sample 一次
     *       （EMF 一帧内会多次把 pose 应用回模型，缓存命中率更高），并打节流的
     *       {@code [VjjNew] anim-opt cache=hit|miss} 证明优化。</li>
     * </ul>
     *
     * @param entityId   实体 id（玩家 entity id；{@code PlayerModelMixin} 用同一 id 查本地动作）
     * @param model      当前渲染的玩家模型
     * @param weightScale 额外权重（EMF hook 传 1.0；最终权重还会乘 overlayWeight）
     * @param lane       管线名（{@code "emf"} / {@code "dummy"}，只用于诊断日志）
     * @return 是否真的叠加了至少一个骨骼（false = 该实体当前没有可播放动作/内容未 sync）
     */
    public static boolean applyToExternalModel(int entityId, PlayerModel model, float weightScale, String lane) {
        if (entityId <= 0 || model == null) {
            return false;
        }
        ActiveAnim active = ACTIVE.get(entityId);
        if (active == null || active.def == null) {
            return false;
        }
        VjjSettings settings = vjjSettingsOrNull();
        float scale = Math.max(0.0F, Math.min(2.0F, weightScale));
        if (settings != null) {
            scale *= (float) Math.max(0.2, Math.min(1.0, settings.overlayWeight));
        }
        boolean posePriority = settings == null || settings.externalPosePriority;
        return applyActive(active, model, scale, posePriority, true,
                lane == null || lane.isBlank() ? "emf" : lane, true);
    }

    /**
     * 采样 + 叠加的共享实现（原生 mixin / 假人 / EMF hook 三个入口共用）。
     *
     * @param tickCache true = 外部管线：按 tick 缓存采样（20Hz 时钟，多帧复用）；false = 原生逐帧平滑
     * @param posePriority true = 先归零 VJJ 覆盖骨骼的旋转再叠加（覆盖外部姿态，优先可见）
     */
    private static boolean applyActive(ActiveAnim active, PlayerModel model, float weightScale,
                                       boolean posePriority, boolean logApply, String lane, boolean tickCache) {
        float elapsed;
        float weight;
        if (tickCache) {
            // 外部管线：时间轴按整数 tick 采样（同 tick 的多次调用共享缓存）
            elapsed = active.ageTicks;
            weight = active.stopping
                    ? AnimSampler.stopWeight(active.def, active.stopTicks)
                    : AnimSampler.blendWeight(active.def, elapsed);
        } else {
            elapsed = active.ageTicks + partialTick();
            weight = currentWeight(active, elapsed);
        }
        weight *= Math.max(0.0F, Math.min(2.0F, weightScale));
        Map<String, BoneOffsets> sampled = tickCache
                ? sampleCached(active, elapsed, weight, lane)
                : AnimSampler.sample(active.def, elapsed, weight);
        if (sampled.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, BoneOffsets> entry : sampled.entrySet()) {
            ModelPart part = part(model, entry.getKey());
            if (part == null) {
                continue;
            }
            BoneOffsets offsets = entry.getValue();
            if (offsets.hasRotation()) {
                if (posePriority) {
                    // VJJ 动作优先：把该骨骼从 EMF/T&M 姿态归零，再叠加 VJJ 旋转
                    part.xRot = 0.0F;
                    part.yRot = 0.0F;
                    part.zRot = 0.0F;
                }
                float[] rotation = offsets.rotationRad();
                part.offsetRotation(new Vector3f(rotation[0], rotation[1], rotation[2]));
            }
            if (offsets.hasPosition()) {
                float[] position = offsets.position();
                part.offsetPos(new Vector3f(position[0], position[1], position[2]));
            }
            if (offsets.hasScale()) {
                float[] scale = offsets.scale();
                part.offsetScale(new Vector3f(scale[0], scale[1], scale[2]));
            }
        }
        if (logApply) {
            logApply(active, elapsed, weight, sampled);
        }
        return true;
    }

    /**
     * 外部管线的按 tick 采样缓存：键 = (entity, anim, tick, 量化权重)。
     * 同 tick 内多次调用（EMF 一帧内多通道/多次 pose copy）都命中缓存；tick 变化才重新采样。
     */
    private static Map<String, BoneOffsets> sampleCached(ActiveAnim active, float elapsed, float weight, String lane) {
        int tick = (int) Math.floor(elapsed);
        int weightKey = Math.round(weight * 1000.0F);
        boolean hit = active.sampleCache != null
                && active.sampleCacheTick == tick
                && active.sampleCacheWeightKey == weightKey;
        if (!hit) {
            active.sampleCache = AnimSampler.sample(active.def, elapsed, weight);
            active.sampleCacheTick = tick;
            active.sampleCacheWeightKey = weightKey;
        }
        logOpt(active, tick, hit, lane);
        return active.sampleCache;
    }

    /** `[VjjNew] anim-opt cache=hit|miss`：只在动作起始前几 tick 打，最多 {@link #OPT_LOG_LIMIT} 条。 */
    private static void logOpt(ActiveAnim active, int tick, boolean hit, String lane) {
        if (tick >= APPLY_LOG_EARLY_TICKS || active.optLogCount >= OPT_LOG_LIMIT) {
            return;
        }
        active.optLogCount++;
        RpgAnimClient.LOGGER.info("[VjjNew] anim-opt cache={} entity={} anim={} t={} lane={}",
                hit ? "hit" : "miss", active.entityId, active.animId, tick, lane);
    }

    /** 客户端面板里的 VJJ 设置（取不到时为 null → 用默认值：EMF 优先、权重 1.0）。 */
    private static VjjSettings vjjSettingsOrNull() {
        try {
            VjjEditorState state = VjjEditorState.get();
            return state == null ? null : state.settings();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * rpgmodel humanoid rig 的骨骼名 → PlayerModel 的 public {@link ModelPart} 字段。
     * 名字与 26.3 玩家模型的网格名完全一致（head/hat/body/left_arm/right_arm/...）。
     * sleeves/jacket/pants 是 arm/body/leg 的子部件，随父骨骼一起动，这里也支持单独驱动。
     */
    private static ModelPart part(PlayerModel model, String bone) {
        return switch (bone) {
            case "head" -> model.head;
            case "hat" -> model.hat;
            case "body" -> model.body;
            case "right_arm" -> model.rightArm;
            case "left_arm" -> model.leftArm;
            case "right_leg" -> model.rightLeg;
            case "left_leg" -> model.leftLeg;
            case "left_sleeve" -> model.leftSleeve;
            case "right_sleeve" -> model.rightSleeve;
            case "left_pants" -> model.leftPants;
            case "right_pants" -> model.rightPants;
            case "jacket" -> model.jacket;
            default -> null;
        };
    }

    /** 该实体当前动作是否压制原版挥臂（HumanoidModelMixin 调用）。 */
    public static boolean suppressesVanillaAttack(int entityId) {
        if (entityId <= 0) {
            return false;
        }
        ActiveAnim active = ACTIVE.get(entityId);
        return active != null && active.def != null && active.def.suppressesVanillaAttack();
    }

    private static float currentWeight(ActiveAnim active, float elapsed) {
        if (active.def == null) {
            return 0.0F;
        }
        if (active.stopping) {
            return AnimSampler.stopWeight(active.def, active.stopTicks + partialTick());
        }
        return AnimSampler.blendWeight(active.def, elapsed);
    }

    private static float partialTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getDeltaTracker() == null) {
            return 0.0F;
        }
        try {
            return Math.max(0.0F, Math.min(1.0F, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
        } catch (RuntimeException exception) {
            return 0.0F;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 日志（§5.8）
    // ─────────────────────────────────────────────────────────────────────

    private static void logApply(ActiveAnim active, float elapsed, float weight, Map<String, BoneOffsets> sampled) {
        int tick = (int) Math.floor(elapsed);
        boolean early = tick < APPLY_LOG_EARLY_TICKS;
        if (active.lastApplyLogTick != Integer.MIN_VALUE) {
            if (early) {
                // 起始阶段每 tick 一行（同一 tick 的多帧不重复）
                if (tick <= active.lastApplyLogTick) {
                    return;
                }
            } else if (tick - active.lastApplyLogTick < APPLY_LOG_INTERVAL) {
                return;
            }
        }
        active.lastApplyLogTick = tick;
        String bone = pickLogBone(sampled);
        BoneOffsets offsets = sampled.get(bone);
        float[] rotation = offsets.hasRotation() ? offsets.rotationRad() : ZERO_ROTATION;
        RpgAnimClient.LOGGER.info("[RpgAnim] anim-apply entity={} anim={} t={} weight={} bone={} rot={}",
                active.entityId, active.animId, tick,
                String.format(Locale.ROOT, "%.2f", weight),
                bone,
                String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                        Math.toDegrees(rotation[0]), Math.toDegrees(rotation[1]), Math.toDegrees(rotation[2])));
    }

    private static String pickLogBone(Map<String, BoneOffsets> sampled) {
        for (String bone : LOG_BONE_ORDER) {
            if (sampled.containsKey(bone)) {
                return bone;
            }
        }
        return sampled.keySet().iterator().next();
    }

    private static void reportEnd(ActiveAnim active, String reason, boolean interrupted) {
        if (active.external) {
            // 假人动作：不发 DonePayload（服务端没有对应玩家动作会话），也不打 anim-end（避免污染断言）
            RpgAnimClient.LOGGER.info("[VjjNew] dummy-anim-end entity={} anim={} ticks={} reason={}",
                    active.entityId, active.animId, active.ageTicks, reason);
            return;
        }
        RpgAnimClient.LOGGER.info("[RpgAnim] anim-end entity={} anim={} ticks={} reason={}",
                active.entityId, active.animId, active.ageTicks, reason);
        ClientNetworking.sendDone(active.entityId, active.animId, active.ageTicks, interrupted, reason);
    }

    private static void warnNoContent() {
        if (!noContentWarned) {
            noContentWarned = true;
            RpgAnimClient.LOGGER.warn("[RpgAnim] play received before content sync; bones will not be driven");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询（HUD / autotest / 键位）
    // ─────────────────────────────────────────────────────────────────────

    public static ActiveAnim active(int entityId) {
        return ACTIVE.get(entityId);
    }

    public static ActiveCutscene currentCutscene() {
        return cutscene;
    }

    /** 动作当前 age（tick）；不在播放或 id 不匹配返回 -1。 */
    public static int actionAge(int entityId, String animId) {
        ActiveAnim active = ACTIVE.get(entityId);
        if (active == null || (animId != null && !active.animId.equals(animId))) {
            return -1;
        }
        return active.ageTicks;
    }

    public static int cutsceneAge() {
        return cutscene == null ? -1 : cutscene.ageTicks;
    }

    /** 动作进度 0..1（循环动作按动作内时间推进，reset 到 0 再走）。 */
    public static float progress(ActiveAnim active) {
        if (active == null || active.durationTicks <= 0) {
            return 0.0F;
        }
        int age = active.loop ? active.ageTicks % active.durationTicks : Math.min(active.ageTicks, active.durationTicks);
        return Math.max(0.0F, Math.min(1.0F, age / (float) active.durationTicks));
    }

    public static float cutsceneProgress(ActiveCutscene active) {
        if (active == null || active.durationTicks <= 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, active.ageTicks / (float) active.durationTicks));
    }

    public static List<String> emoteIds() {
        return emoteIds;
    }

    public static boolean hasContent() {
        return content != null;
    }

    public static AnimContent content() {
        return content;
    }
}
