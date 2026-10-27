package com.vjj.camera;

import com.vjj.VjjNew;
import com.vjj.editor.VjjEditorService;
import com.vjj.net.VjjNet;
import com.vjj.net.VjjPayloads;
import com.vjj.store.CameraClip;
import com.vjj.store.CameraKey;
import com.vjj.store.VjjConfigStore;
import com.vjj.store.VjjSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运镜服务端模块（镜头编辑 / 播放 / 变焦 / 强制镜头 / 锁死行动 / 隐藏玩家）。
 *
 * <p><b>该文件由 Lead 定契约、camera-dev 实现</b>：方法签名固定，GUI/命令/场景管理器按此调用。
 * 见 docs/VJJ-DESIGN.md §3.1。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>关键帧镜头播放</b>：从 {@link VjjConfigStore#clip(String)} 取镜头 → 序列化成 JSON →
 *       发 {@link VjjPayloads.ClipPlayPayload}（整段轨迹在客户端演算），同时记一张活动镜头表，
 *       到点 / 掉线 / 停服统一收尾（{@code clip-stop}）；</li>
 *   <li><b>锁死行动</b>：锁定瞬间保存位置/朝向，之后每个服务端 tick 校正——玩家自己/外力让它偏出
 *       {@link #LOCK_TOLERANCE} 格或带着速度就 {@code teleportTo} 拉回并清零速度；</li>
 *   <li><b>隐藏玩家</b>：服务端 {@code setInvisible}；</li>
 *   <li><b>强制变焦</b>：发 {@link VjjPayloads.ZoomPayload}；</li>
 *   <li><b>可视化面板</b>：{@link #handleAction} 处理运镜编辑动作，{@link #sendSnapshot} 下发整包数据。</li>
 * </ul>
 *
 * <p>日志契约（E2E grep，见 docs/VJJ-DESIGN.md §7）：</p>
 * <pre>
 * [VjjNew] clip-play player=&lt;name&gt; clip=&lt;id&gt; duration=&lt;n&gt; keys=&lt;n&gt; source=&lt;src&gt; broadcast=&lt;b&gt;
 * [VjjNew] clip-stop player=&lt;name&gt; clip=&lt;id&gt; ticks=&lt;n&gt; reason=&lt;...&gt;
 * [VjjNew] zoom-request player=&lt;name&gt; factor=&lt;f&gt; duration=&lt;n&gt; reason=&lt;...&gt;
 * [VjjNew] lock-set player=&lt;name&gt; locked=&lt;b&gt; reason=&lt;...&gt;
 * [VjjNew] hide-set player=&lt;name&gt; hidden=&lt;b&gt; reason=&lt;...&gt;
 * </pre>
 */
public final class VjjCameraServer {
    /** 锁定时允许的位置漂移（格）；超过就拉回。 */
    private static final double LOCK_TOLERANCE = 0.15;
    /** 锁定时允许的朝向漂移（度）。 */
    private static final float LOCK_ROTATION_TOLERANCE = 1.0F;
    /** 速度平方阈值（低于它视为静止）。 */
    private static final double LOCK_VELOCITY_EPSILON = 1.0E-6;
    /** lock-correct 日志节流（tick）。 */
    private static final long LOCK_CORRECT_LOG_INTERVAL = 10L;

    /** 当前被服务端锁死行动的玩家（uuid）。 */
    private static final Set<UUID> LOCKED = ConcurrentHashMap.newKeySet();
    /** 当前被服务端隐藏（invisibility）的玩家（uuid）。 */
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    /** 锁定瞬间的位置/朝向（每 tick 校正的基准）。 */
    private static final Map<UUID, LockPose> LOCK_POSES = new ConcurrentHashMap<>();
    /** 正在播放关键帧镜头的玩家（uuid → 活动镜头）。 */
    private static final Map<UUID, Play> PLAYS = new ConcurrentHashMap<>();
    /** lock-correct 日志节流用。 */
    private static long lastLockCorrectLogTick = Long.MIN_VALUE;

    private VjjCameraServer() {
    }

    /** 一段正在播放的镜头（服务端权威的收尾依据）。 */
    private static final class Play {
        final UUID playerId;
        final String playerName;
        final String clipId;
        final int durationTicks;
        final int keyCount;
        final long startTick;
        final String source;
        final boolean broadcast;
        boolean lockApplied;
        boolean hideApplied;

        Play(UUID playerId, String playerName, String clipId, int durationTicks, int keyCount,
             long startTick, String source, boolean broadcast) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.clipId = clipId;
            this.durationTicks = durationTicks;
            this.keyCount = keyCount;
            this.startTick = startTick;
            this.source = source;
            this.broadcast = broadcast;
        }
    }

    /** 锁定瞬间记录的位姿。 */
    private record LockPose(double x, double y, double z, float yRot, float xRot) {
        static LockPose of(ServerPlayer player) {
            return new LockPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }
    }

    public static void init() {
        VjjNew.LOGGER.info("[VjjNew] camera module init clips={} tolerance={} broadcast_setting={}",
                VjjConfigStore.get().clips().size(), LOCK_TOLERANCE,
                VjjConfigStore.get().settings().broadcastCamera);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 每 tick：镜头到点收尾 + 锁行动位置校正
    // ─────────────────────────────────────────────────────────────────────

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = server.getTickCount();
        tickPlays(server, now);
        tickLocked(server, now);
    }

    private static void tickPlays(MinecraftServer server, long now) {
        if (PLAYS.isEmpty()) {
            return;
        }
        for (Play play : new ArrayList<>(PLAYS.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(play.playerId);
            if (player == null) {
                // 掉线：立即从活动表移除并打收尾日志（恢复动作随实体卸载）
                PLAYS.remove(play.playerId);
                logClipStop(play, now, "quit");
                continue;
            }
            if (now - play.startTick >= play.durationTicks) {
                stop(player, "finished");
            }
        }
    }

    private static void tickLocked(MinecraftServer server, long now) {
        if (LOCKED.isEmpty()) {
            return;
        }
        for (UUID playerId : LOCKED) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                continue;
            }
            LockPose pose = LOCK_POSES.get(playerId);
            if (pose == null) {
                // 兜底：没有基准就记当前位姿
                LOCK_POSES.put(playerId, LockPose.of(player));
                continue;
            }
            double dx = player.getX() - pose.x();
            double dy = player.getY() - pose.y();
            double dz = player.getZ() - pose.z();
            double offset = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vec3 velocity = player.getDeltaMovement();
            double speedSqr = velocity.lengthSqr();
            float yawDrift = Math.abs(Mth.wrapDegrees(player.getYRot() - pose.yRot()));
            float pitchDrift = Math.abs(player.getXRot() - pose.xRot());
            if (offset <= LOCK_TOLERANCE && speedSqr <= LOCK_VELOCITY_EPSILON
                    && yawDrift <= LOCK_ROTATION_TOLERANCE && pitchDrift <= LOCK_ROTATION_TOLERANCE) {
                continue;
            }
            // 拉回锁定位姿并清零速度（双保险：客户端锁输入 + 服务端校正）
            player.teleportTo(pose.x(), pose.y(), pose.z());
            player.setYRot(pose.yRot());
            player.setXRot(pose.xRot());
            player.setDeltaMovement(Vec3.ZERO);
            logLockCorrect(player, offset, now);
        }
    }

    private static void logLockCorrect(ServerPlayer player, double offset, long now) {
        if (lastLockCorrectLogTick != Long.MIN_VALUE && now - lastLockCorrectLogTick < LOCK_CORRECT_LOG_INTERVAL) {
            return;
        }
        lastLockCorrectLogTick = now;
        VjjNew.LOGGER.info("[VjjNew] lock-correct player={} offset={} pos={}",
                playerName(player), fmt(offset), fmtVec(player.position()));
    }

    public static void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        Play play = PLAYS.remove(id);
        if (play != null) {
            logClipStop(play, tickOf(player), "quit");
        }
        LOCKED.remove(id);
        HIDDEN.remove(id);
        LOCK_POSES.remove(id);
    }

    public static void onServerStopping(MinecraftServer server) {
        long now = server == null ? 0L : server.getTickCount();
        for (Play play : new ArrayList<>(PLAYS.values())) {
            logClipStop(play, now, "server-stop");
        }
        PLAYS.clear();
        LOCKED.clear();
        LOCK_POSES.clear();
        // 停服时把被隐藏的玩家复原（避免下次进服仍是隐形）
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null && player.isInvisible()) {
                    player.setInvisible(false);
                }
            }
        }
        HIDDEN.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 播放
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 给目标（默认自己，broadcast 时所有在线玩家）播放一段可编辑镜头。
     *
     * @return 实际时长（tick）；失败返回 -1 并记日志
     */
    public static int playClip(ServerPlayer target, String clipId, String source, boolean broadcast) {
        String sourceTag = sourceTag(source);
        if (target == null) {
            VjjNew.LOGGER.info("[VjjNew] clip-play player=- clip={} duration=0 keys=0 source={} broadcast={} result=-1 "
                    + "reason=no-target", clipId, sourceTag, broadcast);
            return -1;
        }
        CameraClip clip = VjjConfigStore.get().clip(clipId == null ? "" : clipId);
        if (clip == null) {
            VjjNew.LOGGER.info("[VjjNew] clip-play player={} clip={} duration=0 keys=0 source={} broadcast={} result=-1 "
                    + "reason=unknown-clip", playerName(target), clipId, sourceTag, broadcast);
            return -1;
        }
        MinecraftServer server = target.level().getServer();
        if (server == null) {
            VjjNew.LOGGER.info("[VjjNew] clip-play player={} clip={} duration=0 keys=0 source={} broadcast={} result=-1 "
                    + "reason=no-server", playerName(target), clipId, sourceTag, broadcast);
            return -1;
        }
        List<ServerPlayer> targets = new ArrayList<>();
        if (broadcast) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null) {
                    targets.add(player);
                }
            }
        }
        if (!targets.contains(target)) {
            targets.add(target);
        }
        return startPlay(targets, clip, target, sourceTag, broadcast);
    }

    /** 给指定玩家列表播放一段已解析的镜头（场景管理器用；返回时长，-1 失败）。 */
    public static int playClipTo(List<ServerPlayer> targets, CameraClip clip, ServerPlayer anchor, String source) {
        String sourceTag = sourceTag(source);
        if (clip == null || anchor == null || targets == null || targets.isEmpty()) {
            VjjNew.LOGGER.info("[VjjNew] clip-play player={} clip={} duration=0 keys=0 source={} broadcast=false result=-1 "
                            + "reason=bad-args",
                    anchor == null ? "-" : playerName(anchor), clip == null ? "-" : clip.id, sourceTag);
            return -1;
        }
        List<ServerPlayer> effective = new ArrayList<>();
        effective.add(anchor);
        for (ServerPlayer player : targets) {
            if (player != null && !effective.contains(player)) {
                effective.add(player);
            }
        }
        return startPlay(effective, clip, anchor, sourceTag, effective.size() > 1);
    }

    private static int startPlay(List<ServerPlayer> targets, CameraClip clip, ServerPlayer anchor,
                                 String source, boolean broadcast) {
        CameraClip snapshot = clip.copy();
        snapshot.ensureUsable();
        String clipId = snapshot.id == null || snapshot.id.isBlank() ? "unnamed" : snapshot.id;
        int duration = Mth.clamp(snapshot.durationTicks, 1, VjjPayloads.MAX_DURATION);
        int keyCount = snapshot.keys == null ? 0 : snapshot.keys.size();
        String json = VjjConfigStore.clipJson(snapshot);
        String label = snapshot.name == null || snapshot.name.isBlank() ? clipId : snapshot.name;
        if (label.length() > 120) {
            // 网络包 writeUtf 有长度上限（ClipPlayPayload.label 256 字节），超长名字先截断避免发不出去
            label = label.substring(0, 120);
        }
        VjjSettings settings = VjjConfigStore.get().settings().copy();
        MinecraftServer server = anchor.level().getServer();
        long now = server == null ? 0L : server.getTickCount();
        int anchorId = anchor.getId();
        int sent = 0;
        for (ServerPlayer player : targets) {
            if (player == null) {
                continue;
            }
            VjjPayloads.ClipPlayPayload payload = new VjjPayloads.ClipPlayPayload(json, duration, label,
                    settings.forceCamera, settings.lockAction, settings.hidePlayer, anchorId, source);
            if (!VjjNet.send(player, payload)) {
                VjjNew.LOGGER.info("[VjjNew] clip-play player={} clip={} duration={} keys={} source={} broadcast={} "
                                + "result=-1 reason=channel", playerName(player), clipId, duration, keyCount,
                        source, broadcast);
                continue;
            }
            Play previous = PLAYS.put(player.getUUID(), new Play(player.getUUID(), playerName(player), clipId,
                    duration, keyCount, now, source, broadcast));
            if (previous != null) {
                logClipStop(previous, now, "replaced");
            }
            sent++;
            VjjNew.LOGGER.info("[VjjNew] clip-play player={} clip={} duration={} keys={} source={} broadcast={} "
                            + "mode={} anchor={}",
                    playerName(player), clipId, duration, keyCount, source, broadcast, snapshot.mode, anchorId);
        }
        return sent == 0 ? -1 : duration;
    }

    /** 停止某玩家的镜头（返回是否真的停了一段）。 */
    public static boolean stop(ServerPlayer target, String reason) {
        if (target == null) {
            return false;
        }
        Play play = PLAYS.remove(target.getUUID());
        if (play == null) {
            return false;
        }
        String effectiveReason = reason == null || reason.isBlank() ? "stop" : reason;
        VjjNet.send(target, new VjjPayloads.ClipStopPayload(effectiveReason));
        logClipStop(play, tickOf(target), effectiveReason);
        // 镜头播放期间由本模块加上的锁/隐藏，结束时复原（被新镜头替换时交给新流程）
        if (!effectiveReason.startsWith("replaced")) {
            if (play.lockApplied) {
                setLocked(target, false, "clip-end");
            }
            if (play.hideApplied) {
                setHidden(target, false, "clip-end");
            }
        }
        return true;
    }

    public static boolean isPlaying(ServerPlayer target) {
        return target != null && PLAYS.containsKey(target.getUUID());
    }

    private static void logClipStop(Play play, long now, String reason) {
        long ticks = Math.max(0L, now - play.startTick);
        VjjNew.LOGGER.info("[VjjNew] clip-stop player={} clip={} ticks={} reason={}",
                play.playerName, play.clipId, ticks, reason);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 变焦 / 锁行动 / 隐藏玩家
    // ─────────────────────────────────────────────────────────────────────

    /** 强制客户端变焦：factor>1 放大，<1 缩小；durationTicks<=0 常驻。 */
    public static void requestZoom(ServerPlayer target, double factor, int durationTicks, String reason) {
        if (target == null) {
            return;
        }
        double clampedFactor = Math.clamp(factor, 0.1, 20.0);
        int clampedDuration = Math.clamp(durationTicks, -1, 6000);
        String effectiveReason = reason == null || reason.isBlank() ? "api" : reason;
        VjjNew.LOGGER.info("[VjjNew] zoom-request player={} factor={} duration={} reason={}",
                playerName(target), clampedFactor, clampedDuration, effectiveReason);
        VjjNet.send(target, new VjjPayloads.ZoomPayload(clampedFactor, clampedDuration, effectiveReason));
    }

    public static void setLocked(ServerPlayer target, boolean locked, String reason) {
        if (target == null) {
            return;
        }
        String effectiveReason = reason == null || reason.isBlank() ? "api" : reason;
        UUID id = target.getUUID();
        if (locked) {
            if (LOCKED.add(id)) {
                // 第一次锁定：保存基准位姿（之后每 tick 与它比对）
                LOCK_POSES.put(id, LockPose.of(target));
            }
        } else {
            LOCKED.remove(id);
            LOCK_POSES.remove(id);
        }
        VjjNet.send(target, new VjjPayloads.LockPayload(locked, effectiveReason));
        VjjNew.LOGGER.info("[VjjNew] lock-set player={} locked={} reason={}",
                playerName(target), locked, effectiveReason);
    }

    public static void setHidden(ServerPlayer target, boolean hidden, String reason) {
        if (target == null) {
            return;
        }
        String effectiveReason = reason == null || reason.isBlank() ? "api" : reason;
        UUID id = target.getUUID();
        if (hidden) {
            HIDDEN.add(id);
        } else {
            HIDDEN.remove(id);
        }
        target.setInvisible(hidden);
        VjjNew.LOGGER.info("[VjjNew] hide-set player={} hidden={} reason={}",
                playerName(target), hidden, effectiveReason);
    }

    public static boolean isLocked(ServerPlayer target) {
        return target != null && LOCKED.contains(target.getUUID());
    }

    public static boolean isHidden(ServerPlayer target) {
        return target != null && HIDDEN.contains(target.getUUID());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 可视化面板
    // ─────────────────────────────────────────────────────────────────────

    /** 处理运镜类 UI 动作；返回给 GUI 的状态行，未处理返回 null。 */
    public static String handleAction(ServerPlayer op, VjjPayloads.EditorActionPayload action) {
        if (op == null || action == null) {
            return null;
        }
        VjjConfigStore store = VjjConfigStore.get();
        String clipId = action.clipId() == null ? "" : action.clipId();
        switch (action.action() == null ? "" : action.action()) {
            case "clip_new": {
                String name = action.s1() == null || action.s1().isBlank() ? null : action.s1();
                CameraClip clip = store.addClip(clipId.isBlank() ? null : clipId, name);
                return "已新建镜头 " + clip.id + "（" + clip.durationTicks + "t，"
                        + (clip.keys == null ? 0 : clip.keys.size()) + " 帧）";
            }
            case "clip_delete": {
                boolean removed = store.removeClip(clipId);
                return removed ? "已删除镜头 " + clipId : "删除失败：镜头不存在 " + clipId;
            }
            case "clip_select": {
                // 纯客户端选择态，服务端无副作用（返回 null 让编辑器服务端记 unknown 更准确）
                return null;
            }
            case "clip_duration": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                int next = Mth.clamp(action.index(), 20, VjjPayloads.MAX_DURATION);
                clip.durationTicks = next;
                clip.normalize();
                VjjNew.LOGGER.info("[VjjNew] clip-duration id={} duration={}", clipId, next);
                return "镜头 " + clipId + " 时长 = " + next + "t（" + clip.keys.size() + " 帧）";
            }
            case "key_add": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                double x = action.x();
                double y = action.y();
                double z = action.z();
                if (clip.isRelative()) {
                    // 相对模式：包里是“捕获瞬间的眼睛世界坐标”，换算成相对锚点眼睛的偏移
                    Vec3 eye = op.getEyePosition();
                    x -= eye.x;
                    y -= eye.y;
                    z -= eye.z;
                }
                int tick = Mth.clamp(action.tick(), 0, clip.durationTicks);
                float fov = action.fov() > 0.0 ? (float) action.fov() : 70.0F;
                CameraKey key = new CameraKey(tick, x, y, z, (float) action.yaw(), (float) action.pitch(),
                        fov, "smooth");
                clip.keys.add(key);
                clip.normalize();
                int index = clip.keys.indexOf(key);
                VjjNew.LOGGER.info("[VjjNew] clip-key-add id={} tick={} pos={} rot={} fov={} keys={}",
                        clipId, tick, fmtVec(new Vec3(x, y, z)),
                        fmtRot(key.yaw, key.pitch), fov, clip.keys.size());
                return "已加关键帧 #" + index + " t=" + tick + "t（共 " + clip.keys.size() + " 帧）";
            }
            case "key_del": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                int index = action.index();
                if (index < 0 || index >= clip.keys.size()) {
                    return "关键帧下标越界: #" + index + "（共 " + clip.keys.size() + " 帧）";
                }
                clip.keys.remove(index);
                VjjNew.LOGGER.info("[VjjNew] clip-key-del id={} index={} keys={}", clipId, index,
                        clip.keys.size());
                return "已删除关键帧 #" + index + "（剩 " + clip.keys.size() + " 帧）";
            }
            case "key_fov": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                CameraKey key = keyAt(clip, action.index());
                if (key == null) {
                    return "关键帧下标越界: #" + action.index();
                }
                float fov = (float) Math.clamp(action.fov() > 0.0 ? action.fov() : 70.0, 1.0, 180.0);
                key.fov = fov;
                return "关键帧 #" + action.index() + " FOV = " + fmt(fov);
            }
            case "key_time": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                CameraKey key = keyAt(clip, action.index());
                if (key == null) {
                    return "关键帧下标越界: #" + action.index();
                }
                key.tick = Mth.clamp(action.tick(), 0, clip.durationTicks);
                clip.normalize();
                return "关键帧时间 = " + key.tick + "t（已重新排序，共 " + clip.keys.size() + " 帧）";
            }
            case "key_ease": {
                CameraClip clip = store.mutableClip(clipId);
                if (clip == null) {
                    return "镜头不存在: " + clipId;
                }
                CameraKey key = keyAt(clip, action.index());
                if (key == null) {
                    return "关键帧下标越界: #" + action.index();
                }
                key.ease = switch (key.easeOrDefault()) {
                    case "linear" -> "smooth";
                    case "smooth" -> "step";
                    default -> "linear";
                };
                return "关键帧 #" + action.index() + " 缓动 = " + key.ease;
            }
            case "clip_play": {
                CameraClip clip = store.clip(clipId);
                if (clip == null) {
                    return "播放失败：镜头不存在 " + clipId;
                }
                VjjSettings settings = store.settings();
                boolean wantLock = settings.lockAction;
                boolean wantHide = settings.hidePlayer;
                if (wantHide) {
                    setHidden(op, true, "gui-play");
                }
                if (wantLock) {
                    setLocked(op, true, "gui-play");
                }
                boolean broadcast = settings.broadcastCamera || "all".equalsIgnoreCase(settings.targetMode);
                int duration = playClip(op, clipId, "gui", broadcast);
                if (duration <= 0) {
                    if (wantLock) {
                        setLocked(op, false, "gui-play-failed");
                    }
                    if (wantHide) {
                        setHidden(op, false, "gui-play-failed");
                    }
                    return "播放失败: " + clipId + "（见服务端日志）";
                }
                Play play = PLAYS.get(op.getUUID());
                if (play != null) {
                    play.lockApplied = wantLock;
                    play.hideApplied = wantHide;
                }
                return "播放镜头 " + clipId + "（" + duration + "t，强制镜头=" + settings.forceCamera
                        + "，锁行动=" + wantLock + "，隐藏=" + wantHide + "）";
            }
            case "clip_stop": {
                boolean stopped = stop(op, "gui");
                return stopped ? "镜头已停止" : "当前没有正在播放的镜头";
            }
            default:
                return null;
        }
    }

    /** 给 op 发送一次完整面板数据快照。 */
    public static void sendSnapshot(ServerPlayer op, String status) {
        VjjEditorService.sendSnapshot(op, status);
    }

    /** 打开可视化面板（tab = hub|camera|anim|settings）。 */
    public static void openEditor(ServerPlayer op, String tab) {
        VjjEditorService.openEditor(op, tab);
    }

    /** 在线玩家名列表（GUI 目标选择用）。 */
    public static List<String> targetNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != null) {
                    names.add(player.getGameProfile().name());
                }
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static CameraKey keyAt(CameraClip clip, int index) {
        if (clip.keys == null || index < 0 || index >= clip.keys.size()) {
            return null;
        }
        return clip.keys.get(index);
    }

    private static String sourceTag(String source) {
        return source == null || source.isBlank() ? "api" : source;
    }

    private static String playerName(ServerPlayer player) {
        return player == null ? "-" : player.getGameProfile().name();
    }

    private static long tickOf(ServerPlayer player) {
        if (player == null) {
            return 0L;
        }
        try {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                return server.getTickCount();
            }
        } catch (Throwable ignored) {
            // 掉线/实体卸载后 level 可能已不可用，用 0 兜底
        }
        return 0L;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmtVec(Vec3 vec) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", vec.x, vec.y, vec.z);
    }

    private static String fmtRot(float yaw, float pitch) {
        return String.format(Locale.ROOT, "%.1f,%.1f", yaw, pitch);
    }
}
