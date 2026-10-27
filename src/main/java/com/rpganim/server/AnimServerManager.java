package com.rpganim.server;

import com.rpganim.RpgAnim;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.net.ModPayloads;
import com.rpganim.net.ServerNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的动作调度器。
 *
 * <ul>
 *   <li>每个玩家同一时刻只有一个动作；新动作按 {@link AnimDefinition#priority()} 决定替换还是被忽略
 *       （命令/过场等"权威来源"总是替换）；</li>
 *   <li>服务端决定时长与 loop，广播给**同维度 64 格内**的所有玩家（含目标本人）；</li>
 *   <li>非循环动作到期自动结束（{@code reason=expired}），循环动作一直播到被停止/替换；</li>
 *   <li>支持 {@code /anim demo}：把全部动作依次演一遍（每段之间 {@link #DEMO_GAP_TICKS} tick）。</li>
 * </ul>
 *
 * <p>日志契约（E2E grep，字段顺序固定，见 {@code docs/contract.md}）：
 * {@code anim-start ...} / {@code anim-end ...} / {@code anim-denied ...} / {@code anim-trigger ...}。</p>
 */
public final class AnimServerManager {
    private static final AnimServerManager INSTANCE = new AnimServerManager();

    /** 同维度广播半径（格）。 */
    public static final int BROADCAST_RADIUS_BLOCKS = 64;
    /** demo 每段动作之间留的空白 tick 数。 */
    public static final int DEMO_GAP_TICKS = 30;
    /** 客户端键位请求的最小间隔（tick）。 */
    public static final int EMOTE_REQUEST_COOLDOWN_TICKS = 20;

    /** 触发类来源（优先级更低时会被当前动作无视）；命令/过场/演示永远可以替换。 */
    private static boolean isTriggerSource(String source) {
        return "attack".equals(source) || "damage".equals(source)
                || "death".equals(source) || "emote-request".equals(source);
    }

    private record ActiveAction(int entityId, String playerName, String animId, int durationTicks,
                                boolean loop, String source, int priority, long startTick, long endTick) {
    }

    private record DemoState(Deque<String> queue, int total, int played, long nextTick) {
    }

    private final Map<UUID, ActiveAction> active = new HashMap<>();
    private final Map<UUID, DemoState> demos = new HashMap<>();
    private final Map<UUID, Long> lastEmoteRequest = new HashMap<>();

    private AnimServerManager() {
    }

    public static AnimServerManager get() {
        return INSTANCE;
    }

    public void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> onServerStopping());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 播放 / 停止
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 播放一个动作。
     *
     * @param target        目标玩家（必须在线且装了 rpganim 客户端）
     * @param animId        动作 id
     * @param durationTicks {@code <=0} 表示用动作默认时长（循环动作则一直播到被停止）
     * @param source        来源标记：command/attack/damage/death/emote-request/cutscene/demo/api
     * @return 实际时长（tick）；失败返回 0
     */
    public int play(ServerPlayer target, String animId, int durationTicks, String source) {
        return play(target, animId, durationTicks, source, null);
    }

    /**
     * 播放一个动作，允许调用方覆盖 loop 标记（过场步骤的 {@code loop} 字段用）。
     *
     * @param loopOverride {@code null} = 用动作自身定义；否则以调用方为准
     */
    public int play(ServerPlayer target, String animId, int durationTicks, String source, Boolean loopOverride) {
        if (target == null) {
            return 0;
        }
        String playerName = nameOf(target);
        AnimDefinition definition = ContentStore.get().animation(animId);
        if (definition == null) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-denied player={} anim={} reason=unknown", playerName, animId);
            return 0;
        }
        if (ContentStore.get().isDisabled(animId)) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-skip player={} anim={} reason=invalid-rig detail={}",
                    playerName, animId, ContentStore.get().disabledReason(animId));
            return 0;
        }
        if (!ServerNetworking.canSendPlay(target)) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-skip player={} anim={} reason=no-client-mod", playerName, animId);
            return 0;
        }

        long now = now(target);
        ActiveAction previous = active.get(target.getUUID());
        if (previous != null && now < previous.endTick() && isTriggerSource(source)
                && previous.priority() > definition.priority()) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-skip player={} anim={} reason=priority current={}",
                    playerName, animId, previous.animId());
            return 0;
        }

        boolean loop = loopOverride != null ? loopOverride : definition.loop();
        int duration;
        long endTick;
        if (durationTicks > 0) {
            duration = Math.min(durationTicks, ModPayloads.MAX_DURATION);
            endTick = now + duration;
        } else if (loop) {
            duration = definition.durationTicks();
            endTick = Long.MAX_VALUE;
        } else {
            duration = definition.durationTicks();
            endTick = now + duration;
        }

        if (previous != null) {
            endAction(target, previous, now < previous.endTick() ? "replaced" : "expired", now);
        }

        ActiveAction action = new ActiveAction(target.getId(), playerName, animId, duration, loop, source,
                definition.priority(), now, endTick);
        active.put(target.getUUID(), action);
        RpgAnim.LOGGER.info("[RpgAnim] anim-start entity={} type=player name={} anim={} duration={} loop={} source={}",
                target.getId(), playerName, animId, duration, loop, source);
        broadcast(target, new ModPayloads.PlayPayload(target.getId(), animId, duration, loop, source));
        return duration;
    }

    /**
     * 停止该玩家当前动作。
     *
     * @param reason {@code stop}（命令）/ {@code quit}（掉线/停服）/ {@code death}（死亡打断）
     * @return 是否确实停掉了一个动作
     */
    public boolean stop(ServerPlayer target, String reason) {
        if (target == null) {
            return false;
        }
        demos.remove(target.getUUID());
        ActiveAction action = active.remove(target.getUUID());
        if (action == null) {
            return false;
        }
        endAction(target, action, reason, now(target));
        return true;
    }

    /** 只取消 demo 队列，不动当前动作（命令显式播放前调用）。 */
    public void cancelDemo(ServerPlayer target) {
        if (target != null) {
            demos.remove(target.getUUID());
        }
    }

    /**
     * 客户端键位请求（C2S EmoteRequestPayload）：校验类别 + 20t 节流后广播。
     */
    public void handleEmoteRequest(ServerPlayer player, String animId) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        String playerName = nameOf(player);
        AnimDefinition definition = ContentStore.get().animation(animId);
        if (definition == null) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-denied player={} anim={} reason=unknown", playerName, animId);
            return;
        }
        if (!definition.isEmote() && !definition.isCombat()) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-denied player={} anim={} reason=category", playerName, animId);
            return;
        }
        long now = now(player);
        Long last = lastEmoteRequest.get(player.getUUID());
        if (last != null && now - last < EMOTE_REQUEST_COOLDOWN_TICKS) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-denied player={} anim={} reason=rate", playerName, animId);
            return;
        }
        lastEmoteRequest.put(player.getUUID(), now);
        RpgAnim.LOGGER.info("[RpgAnim] anim-trigger player={} anim={} source=emote-request weapon=none target=minecraft:player",
                playerName, animId);
        play(player, animId, -1, "emote-request");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Demo（依次播放全部动作）
    // ─────────────────────────────────────────────────────────────────────

    /** @return demo 的总步数；0 表示没有可演示的动作 */
    public int startDemo(ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        cancelDemo(target);
        List<String> ids = ContentStore.get().animationIds();
        if (ids.isEmpty()) {
            return 0;
        }
        demos.put(target.getUUID(), new DemoState(new ArrayDeque<>(ids), ids.size(), 0, 0L));
        RpgAnim.LOGGER.info("[RpgAnim] demo-start player={} total={}", nameOf(target), ids.size());
        return ids.size();
    }

    public boolean isDemoRunning(ServerPlayer target) {
        return target != null && demos.containsKey(target.getUUID());
    }

    private void tickDemos(MinecraftServer server, long now) {
        if (demos.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, DemoState>> iterator = demos.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DemoState> entry = iterator.next();
            UUID id = entry.getKey();
            DemoState state = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (active.containsKey(id) || now < state.nextTick()) {
                continue;
            }
            if (state.queue().isEmpty()) {
                iterator.remove();
                RpgAnim.LOGGER.info("[RpgAnim] demo-done player={} total={}", nameOf(player), state.total());
                continue;
            }
            String next = state.queue().pollFirst();
            AnimDefinition definition = ContentStore.get().animation(next);
            int index = state.played() + 1;
            if (definition == null) {
                demos.put(id, new DemoState(state.queue(), state.total(), index, now + 5));
                continue;
            }
            // 显式给时长：循环动作在 demo 里也只演一个周期，巡演不会卡住。
            int played = play(player, next, definition.durationTicks(), "demo");
            demos.put(id, new DemoState(state.queue(), state.total(), index,
                    now + (played > 0 ? played : 0) + DEMO_GAP_TICKS));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询（命令 / API 用）
    // ─────────────────────────────────────────────────────────────────────

    public boolean isPlaying(ServerPlayer target) {
        return target != null && active.containsKey(target.getUUID());
    }

    public String activeAnimId(ServerPlayer target) {
        ActiveAction action = target == null ? null : active.get(target.getUUID());
        return action == null ? null : action.animId();
    }

    /** 当前动作剩余 tick；循环动作或没有动作为 -1。 */
    public long remainingTicks(ServerPlayer target) {
        ActiveAction action = target == null ? null : active.get(target.getUUID());
        if (action == null || action.endTick() == Long.MAX_VALUE) {
            return -1;
        }
        return Math.max(0, action.endTick() - now(target));
    }

    /** 当前动作的一行描述（{@code /anim status} 用；没有返回 null）。 */
    public String describeActive(ServerPlayer target) {
        ActiveAction action = target == null ? null : active.get(target.getUUID());
        if (action == null) {
            return null;
        }
        long now = now(target);
        String remaining = action.endTick() == Long.MAX_VALUE
                ? "loop"
                : Long.toString(Math.max(0, action.endTick() - now));
        return action.animId() + " duration=" + action.durationTicks() + " remaining=" + remaining
                + " source=" + action.source();
    }

    // ─────────────────────────────────────────────────────────────────────
    // tick / 收尾
    // ─────────────────────────────────────────────────────────────────────

    private void onServerTick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!active.isEmpty()) {
            Iterator<Map.Entry<UUID, ActiveAction>> iterator = active.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ActiveAction> entry = iterator.next();
                ActiveAction action = entry.getValue();
                if (now < action.endTick()) {
                    continue;
                }
                iterator.remove();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    RpgAnim.LOGGER.info("[RpgAnim] anim-end entity={} name={} anim={} ticks={} reason=quit",
                            action.entityId(), action.playerName(), action.animId(), action.durationTicks());
                } else {
                    broadcastStop(player, action.animId(), "expired");
                    RpgAnim.LOGGER.info("[RpgAnim] anim-end entity={} name={} anim={} ticks={} reason=expired",
                            action.entityId(), action.playerName(), action.animId(), action.durationTicks());
                }
            }
        }
        tickDemos(server, now);
    }

    private void onDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        demos.remove(player.getUUID());
        lastEmoteRequest.remove(player.getUUID());
        ActiveAction action = active.remove(player.getUUID());
        if (action != null) {
            RpgAnim.LOGGER.info("[RpgAnim] anim-end entity={} name={} anim={} ticks={} reason=quit",
                    action.entityId(), action.playerName(), action.animId(), action.durationTicks());
        }
    }

    private void onServerStopping() {
        for (Map.Entry<UUID, ActiveAction> entry : active.entrySet()) {
            ActiveAction action = entry.getValue();
            RpgAnim.LOGGER.info("[RpgAnim] anim-end entity={} name={} anim={} ticks={} reason=quit",
                    action.entityId(), action.playerName(), action.animId(), action.durationTicks());
        }
        active.clear();
        demos.clear();
        lastEmoteRequest.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────────────

    /** 广播停止包并记 anim-end（调用方负责从 active 里移除）。 */
    private void endAction(ServerPlayer target, ActiveAction action, String reason, long now) {
        broadcastStop(target, action.animId(), reason);
        long played = Math.max(0, Math.min(now - action.startTick(), action.durationTicks()));
        RpgAnim.LOGGER.info("[RpgAnim] anim-end entity={} name={} anim={} ticks={} reason={}",
                action.entityId(), action.playerName(), action.animId(), played, reason);
    }

    private void broadcastStop(ServerPlayer anchor, String animId, String reason) {
        broadcast(anchor, new ModPayloads.StopPayload(anchor.getId(), animId, reason));
    }

    private void broadcast(ServerPlayer anchor, CustomPacketPayload payload) {
        ServerLevel level = anchor.level();
        double radiusSq = (double) BROADCAST_RADIUS_BLOCKS * BROADCAST_RADIUS_BLOCKS;
        List<ServerPlayer> observers = level.players();
        for (ServerPlayer observer : observers) {
            if (observer.distanceToSqr(anchor) > radiusSq) {
                continue;
            }
            ServerNetworking.send(observer, payload);
        }
    }

    private static long now(ServerPlayer player) {
        return player.level().getServer().getTickCount();
    }

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
