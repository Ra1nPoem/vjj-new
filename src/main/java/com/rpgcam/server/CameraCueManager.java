package com.rpgcam.server;

import com.rpgcam.RpgCamera;
import com.rpgcam.cue.AnchorType;
import com.rpgcam.cue.CueRegistry;
import com.rpgcam.cue.CueSpec;
import com.rpgcam.net.ModNetworking;
import com.rpgcam.net.ModPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的镜头调度器：
 * <ul>
 *   <li>每个玩家同一时刻只有一段镜头（新的会替换旧的）；</li>
 *   <li>由服务端决定时长、锚点与随机种子，客户端只负责演算；</li>
 *   <li>到时由服务端发 {@code stop} 兜底，客户端也会按 duration 自行收尾（双保险）；</li>
 *   <li>支持 {@code /cinematic demo}：把全部样式依次演一遍（RPG 演出/验收用）。</li>
 * </ul>
 *
 * <p>日志是 E2E 的机器可读契约，格式见 README「日志契约」一节，改动需同步脚本。</p>
 */
public final class CameraCueManager {
    private static final CameraCueManager INSTANCE = new CameraCueManager();

    /** 每段 demo 之间留的空白 tick 数（够截图/换场）。 */
    public static final int DEMO_GAP_TICKS = 30;

    private record ActiveCue(String styleId, int durationTicks, long endTick, String label,
                             String source, String anchorDesc, long startTick) {
    }

    private record DemoState(Deque<String> queue, int total, long nextTick) {
    }

    private final Map<UUID, ActiveCue> active = new HashMap<>();
    private final Map<UUID, DemoState> demos = new HashMap<>();
    private long tickCounter;

    private CameraCueManager() {
    }

    public static CameraCueManager get() {
        return INSTANCE;
    }

    public void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        RpgCamera.LOGGER.info("[RpgCamera] cue manager events registered");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 对外：播放/停止
    // ─────────────────────────────────────────────────────────────────────

    /** API 入口（其它 mod 调用），source=interop（表示跨 mod 联动；契约/verifier 依赖这个值）。 */
    public int playFromApi(ServerPlayer player, String styleId, int durationTicks,
                           Entity target, Vec3 anchorPos, String label) {
        return play(player, styleId, durationTicks, target, anchorPos, label, "interop");
    }

    /**
     * 播放一段镜头。
     *
     * @param durationTicks &lt;=0 表示用样式默认时长
     * @param target        目标实体（AnchorType.TARGET 用；可为 null）
     * @param anchorPos     固定坐标锚点（AnchorType.POSITION 用；可为 null）
     * @param label         overlay 附加文字（可为 null）
     * @param source        来源标记：command/api/demo/interop/preview/test
     * @return 实际时长；失败 -1
     */
    public int play(ServerPlayer player, String styleId, int durationTicks,
                    Entity target, Vec3 anchorPos, String label, String source) {
        if (player == null || player.hasDisconnected()) {
            RpgCamera.LOGGER.warn("[RpgCamera] cue-skip player=null style={} reason=offline", styleId);
            return -1;
        }
        CueSpec spec = CueRegistry.get(styleId);
        if (spec == null) {
            RpgCamera.LOGGER.warn("[RpgCamera] cue-skip player={} style={} reason=unknown-style",
                    nameOf(player), styleId);
            return -1;
        }
        if (!ModNetworking.canSendCue(player)) {
            RpgCamera.LOGGER.warn("[RpgCamera] cue-skip player={} style={} reason=no-client-mod",
                    nameOf(player), styleId);
            return -1;
        }

        int duration = durationTicks > 0
                ? Math.min(durationTicks, ModPayloads.CuePayload.MAX_DURATION)
                : spec.durationTicks();

        // 锚点兜底：需要 TARGET 但没给实体时退回玩家本人；需要 POSITION 但没坐标时退回玩家脚下
        Entity effectiveTarget = target;
        Vec3 effectivePos = null;
        if (spec.anchor() == AnchorType.TARGET) {
            if (effectiveTarget == null) {
                RpgCamera.LOGGER.warn("[RpgCamera] cue-anchor-fallback player={} style={} wanted=target got=player",
                        nameOf(player), styleId);
                effectiveTarget = player;
            }
        } else if (spec.anchor() == AnchorType.POSITION) {
            effectivePos = anchorPos != null ? anchorPos : player.position();
        }

        // 旧的先停（替换语义）
        ActiveCue previous = active.remove(player.getUUID());
        if (previous != null) {
            ModNetworking.sendStop(player, new ModPayloads.StopPayload(previous.styleId(), "replaced"));
            RpgCamera.LOGGER.info("[RpgCamera] cue-stop player={} style={} duration={} source={} reason=replaced",
                    nameOf(player), previous.styleId(), previous.durationTicks(), previous.source());
        }

        int anchorEntityId = effectiveTarget == null ? -1 : effectiveTarget.getId();
        boolean hasAnchorPos = effectivePos != null;
        Vec3 pos = effectivePos == null ? Vec3.ZERO : effectivePos;
        int seed = (int) (player.getUUID().getLeastSignificantBits() ^ (tickCounter * 31L));
        String safeLabel = label == null ? "" : label;
        long endTick = tickCounter + duration;

        String anchorDesc = anchorEntityId >= 0
                ? ("entity:" + anchorEntityId)
                : (hasAnchorPos ? String.format("pos:%.2f,%.2f,%.2f", pos.x(), pos.y(), pos.z()) : "player");

        ModNetworking.sendCue(player, new ModPayloads.CuePayload(
                spec.id(), duration, anchorEntityId, hasAnchorPos, pos.x(), pos.y(), pos.z(), safeLabel, seed));

        active.put(player.getUUID(), new ActiveCue(spec.id(), duration, endTick, safeLabel, source,
                anchorDesc, tickCounter));
        RpgCamera.LOGGER.info("[RpgCamera] cue-sent player={} style={} duration={} source={} anchor={} label=\"{}\" endTick={}",
                nameOf(player), spec.id(), duration, source, anchorDesc, safeLabel, endTick);
        return duration;
    }

    /** 停止该玩家当前镜头。 */
    public boolean stop(ServerPlayer player, String reason) {
        if (player == null) {
            return false;
        }
        ActiveCue cue = active.remove(player.getUUID());
        demos.remove(player.getUUID());
        if (cue == null) {
            return false;
        }
        ModNetworking.sendStop(player, new ModPayloads.StopPayload(cue.styleId(), reason));
        RpgCamera.LOGGER.info("[RpgCamera] cue-stop player={} style={} duration={} source={} reason={}",
                nameOf(player), cue.styleId(), cue.durationTicks(), cue.source(), reason);
        return true;
    }

    public boolean isPlaying(ServerPlayer player) {
        return player != null && active.containsKey(player.getUUID());
    }

    /** 当前镜头信息（没有则 null）。 */
    public String describeActive(ServerPlayer player) {
        ActiveCue cue = active.get(player.getUUID());
        if (cue == null) {
            return null;
        }
        return cue.styleId() + " remaining=" + Math.max(0, cue.endTick() - tickCounter) + "/" + cue.durationTicks()
                + " source=" + cue.source() + " anchor=" + cue.anchorDesc()
                + (cue.label().isEmpty() ? "" : " label=" + cue.label());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Demo：串行播放多个样式
    // ─────────────────────────────────────────────────────────────────────

    public void startDemo(ServerPlayer player, List<String> styles, int gapTicks) {
        UUID id = player.getUUID();
        stop(player, "demo-restart");
        Deque<String> queue = new ArrayDeque<>(styles);
        demos.put(id, new DemoState(queue, styles.size(), 0L));
        RpgCamera.LOGGER.info("[RpgCamera] demo-start player={} total={} gap={} styles={}",
                nameOf(player), styles.size(), gapTicks, String.join(",", styles));
    }

    public void startDemoAll(ServerPlayer player) {
        startDemo(player, new ArrayList<>(CueRegistry.ids()), DEMO_GAP_TICKS);
    }

    public boolean isDemoRunning(ServerPlayer player) {
        return player != null && demos.containsKey(player.getUUID());
    }

    public void cancelDemo(ServerPlayer player) {
        if (player != null) {
            demos.remove(player.getUUID());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // tick
    // ─────────────────────────────────────────────────────────────────────

    private void onServerTick(MinecraftServer server) {
        tickCounter++;
        long now = tickCounter;
        Iterator<Map.Entry<UUID, ActiveCue>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveCue> entry = it.next();
            ActiveCue cue = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                RpgCamera.LOGGER.info("[RpgCamera] cue-drop player=offline style={} reason=player-left", cue.styleId());
                continue;
            }
            if (now >= cue.endTick()) {
                it.remove();
                ModNetworking.sendStop(player, new ModPayloads.StopPayload(cue.styleId(), "finished"));
                RpgCamera.LOGGER.info("[RpgCamera] cue-end player={} style={} duration={} source={} reason=finished",
                        nameOf(player), cue.styleId(), cue.durationTicks(), cue.source());
            }
        }
        tickDemos(server, now);
    }

    private void tickDemos(MinecraftServer server, long now) {
        Iterator<Map.Entry<UUID, DemoState>> it = demos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DemoState> entry = it.next();
            UUID id = entry.getKey();
            DemoState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                it.remove();
                continue;
            }
            if (active.containsKey(id) || now < state.nextTick()) {
                continue; // 等当前这段播完 / 等间隔
            }
            if (state.queue().isEmpty()) {
                it.remove();
                RpgCamera.LOGGER.info("[RpgCamera] demo-done player={} total={}", nameOf(player), state.total());
                continue;
            }
            String next = state.queue().pollFirst();
            int index = state.total() - state.queue().size();
            int played = play(player, next, -1, null, null, "", "demo");
            if (played > 0) {
                RpgCamera.LOGGER.info("[RpgCamera] demo-step player={} index={} total={} style={} duration={}",
                        nameOf(player), index, state.total(), next, played);
                demos.put(id, new DemoState(state.queue(), state.total(),
                        now + played + DEMO_GAP_TICKS));
            } else {
                demos.put(id, new DemoState(state.queue(), state.total(), now + 10));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
