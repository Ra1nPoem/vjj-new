package com.rpganim.cutscene;

import com.rpganim.RpgAnim;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.anim.CutsceneCamera;
import com.rpganim.anim.CutsceneDefinition;
import com.rpganim.anim.CutsceneStep;
import com.rpganim.interop.RpgAnimInterop;
import com.rpganim.net.ModPayloads;
import com.rpganim.net.ServerNetworking;
import com.rpganim.server.AnimServerManager;
import com.rpganim.server.ContentStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端权威的过场时间轴：镜头（rpgcam soft-dep）+ 按 {@code at} 触发的动作步骤 + 客户端 HUD 包。
 *
 * <ul>
 *   <li>t=0 发 {@code CutscenePayload(starting=true)}；镜头按 {@code camera.at} 触发（通常为 0），
 *       调 {@link RpgAnimInterop#playCamera}，失败（没装 rpgcam）只记 {@code result=-1}，动作照播；</li>
 *   <li>每个动作步骤到点由 {@link AnimServerManager#play} 广播 {@code PlayPayload}
 *       （{@code source=cutscene}；循环步骤播到下一步/过场结束）；</li>
 *   <li>duration 到点发 {@code CutscenePayload(starting=false)} 并记 {@code cutscene-end reason=finished}；</li>
 *   <li>玩家掉线/停服 → {@code reason=quit}，命令停止 → {@code reason=stop}，均收尾并停镜头。</li>
 * </ul>
 *
 * <p>日志契约（E2E grep，字段顺序固定）：{@code cutscene-start / cutscene-step / cutscene-camera /
 * cutscene-end}，见 docs/contract.md。</p>
 */
public final class CutsceneManager {
    private static final CutsceneManager INSTANCE = new CutsceneManager();

    /** {@code /cutscene demo} 每段过场之间留的空白 tick 数。 */
    public static final int DEMO_GAP_TICKS = 30;

    /** 进行中的过场（可变：nextStep / cameraStarted 由 tick 推进）。 */
    private static final class ActiveCutscene {
        private final int entityId;
        private final String playerName;
        private final CutsceneDefinition definition;
        private final long startTick;
        private final long endTick;
        private int nextStep;
        private boolean cameraStarted;

        private ActiveCutscene(int entityId, String playerName, CutsceneDefinition definition,
                               long startTick, long endTick) {
            this.entityId = entityId;
            this.playerName = playerName;
            this.definition = definition;
            this.startTick = startTick;
            this.endTick = endTick;
        }
    }

    private record DemoState(Deque<String> queue, int total, long nextTick) {
    }

    private final Map<UUID, ActiveCutscene> active = new HashMap<>();
    private final Map<UUID, DemoState> demos = new HashMap<>();

    private CutsceneManager() {
    }

    public static CutsceneManager get() {
        return INSTANCE;
    }

    public void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> stop(handler.player, "quit"));
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 播放 / 停止
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 给玩家播放一段过场。
     *
     * @return 过场时长（tick）；失败返回 0
     */
    public int play(ServerPlayer target, String cutsceneId) {
        if (target == null || target.hasDisconnected()) {
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-skip player=null id={} reason=offline", cutsceneId);
            return 0;
        }
        String playerName = nameOf(target);
        CutsceneDefinition definition = ContentStore.get().cutscene(cutsceneId);
        if (definition == null) {
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-skip player={} id={} reason=unknown", playerName, cutsceneId);
            return 0;
        }
        if (!ServerNetworking.canSendCutscene(target)) {
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-skip player={} id={} reason=no-client-mod", playerName, cutsceneId);
            return 0;
        }

        // 同一玩家同一时刻只有一段过场：旧的按 reason=stop 收尾。
        ActiveCutscene previous = active.remove(target.getUUID());
        if (previous != null) {
            finish(target, previous, "stop");
        }

        long now = now(target);
        int duration = definition.durationTicks();
        ActiveCutscene state = new ActiveCutscene(target.getId(), playerName, definition, now, now + duration);
        active.put(target.getUUID(), state);

        String cameraStyle = definition.hasCamera() ? definition.camera().style() : "none";
        RpgAnim.LOGGER.info("[RpgAnim] cutscene-start player={} id={} duration={} steps={} camera={}",
                playerName, definition.id(), duration, definition.steps().size(), cameraStyle);
        ServerNetworking.sendCutscene(target, new ModPayloads.CutscenePayload(
                definition.id(), definition.titleKey(), duration, true, cameraPayloadStyle(definition)));
        // at=0 的镜头/动作立刻触发，不依赖下一 tick。
        processTimeline(target, state, now);
        return duration;
    }

    /**
     * 停止该玩家当前过场。
     *
     * @param reason {@code stop}（命令）/ {@code quit}（掉线/停服）
     * @return 是否确实停掉了一段过场
     */
    public boolean stop(ServerPlayer player, String reason) {
        if (player == null) {
            return false;
        }
        demos.remove(player.getUUID());
        ActiveCutscene state = active.remove(player.getUUID());
        if (state == null) {
            return false;
        }
        finish(player, state, reason);
        return true;
    }

    /** 只取消 demo 队列，不动当前过场（命令显式播放前调用）。 */
    public void cancelDemo(ServerPlayer target) {
        if (target != null) {
            demos.remove(target.getUUID());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Demo（依次播放全部过场）
    // ─────────────────────────────────────────────────────────────────────

    /** @return demo 的总段数；0 表示没有可演示的过场 */
    public int startDemo(ServerPlayer target) {
        if (target == null) {
            return 0;
        }
        cancelDemo(target);
        List<String> ids = ContentStore.get().cutsceneIds();
        if (ids.isEmpty()) {
            return 0;
        }
        demos.put(target.getUUID(), new DemoState(new ArrayDeque<>(ids), ids.size(), 0L));
        RpgAnim.LOGGER.info("[RpgAnim] cutscene-demo-start player={} total={}", nameOf(target), ids.size());
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
                RpgAnim.LOGGER.info("[RpgAnim] cutscene-demo-done player={} total={}", nameOf(player), state.total());
                continue;
            }
            String next = state.queue().pollFirst();
            CutsceneDefinition definition = ContentStore.get().cutscene(next);
            if (definition == null) {
                demos.put(id, new DemoState(state.queue(), state.total(), now + 10));
                continue;
            }
            int played = play(player, next);
            demos.put(id, new DemoState(state.queue(), state.total(), now + played + DEMO_GAP_TICKS));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询（命令 / API 用）
    // ─────────────────────────────────────────────────────────────────────

    public boolean isPlaying(ServerPlayer target) {
        return target != null && active.containsKey(target.getUUID());
    }

    public String activeCutsceneId(ServerPlayer target) {
        ActiveCutscene state = target == null ? null : active.get(target.getUUID());
        return state == null ? null : state.definition.id();
    }

    /** 当前过场剩余 tick；没有过场返回 -1。 */
    public long remainingTicks(ServerPlayer target) {
        ActiveCutscene state = target == null ? null : active.get(target.getUUID());
        if (state == null) {
            return -1;
        }
        return Math.max(0, state.endTick - now(target));
    }

    /** 当前过场的一行描述（{@code /cutscene status} 用；没有返回 null）。 */
    public String describeActive(ServerPlayer target) {
        ActiveCutscene state = target == null ? null : active.get(target.getUUID());
        if (state == null) {
            return null;
        }
        long remaining = Math.max(0, state.endTick - now(target));
        String camera = state.definition.hasCamera() ? state.definition.camera().style() : "none";
        return state.definition.id() + " duration=" + state.definition.durationTicks()
                + " remaining=" + remaining + " steps=" + state.nextStep + "/" + state.definition.steps().size()
                + " camera=" + camera;
    }

    // ─────────────────────────────────────────────────────────────────────
    // tick / 收尾
    // ─────────────────────────────────────────────────────────────────────

    private void onServerTick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!active.isEmpty()) {
            Iterator<Map.Entry<UUID, ActiveCutscene>> iterator = active.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ActiveCutscene> entry = iterator.next();
                ActiveCutscene state = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    iterator.remove();
                    RpgAnim.LOGGER.info("[RpgAnim] cutscene-end player={} id={} reason=quit",
                            state.playerName, state.definition.id());
                    continue;
                }
                processTimeline(player, state, now);
                if (now >= state.endTick) {
                    iterator.remove();
                    finish(player, state, "finished");
                }
            }
        }
        tickDemos(server, now);
    }

    /** 推进时间轴：到点的镜头与动作步骤各处理一次。 */
    private void processTimeline(ServerPlayer player, ActiveCutscene state, long now) {
        CutsceneDefinition definition = state.definition;
        long elapsed = now - state.startTick;

        CutsceneCamera camera = definition.camera();
        if (!state.cameraStarted && camera != null && camera.at() <= elapsed) {
            state.cameraStarted = true;
            int result = RpgAnimInterop.playCamera(player, definition.id(), camera.style(),
                    camera.durationTicks(), definition.titleKey());
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-step player={} id={} at={} kind=camera ref={} result={}",
                    state.playerName, definition.id(), camera.at(), camera.style(),
                    result > 0 ? "ok" : "skip");
        }

        List<CutsceneStep> steps = definition.steps();
        while (state.nextStep < steps.size() && steps.get(state.nextStep).at() <= elapsed) {
            CutsceneStep step = steps.get(state.nextStep);
            int index = state.nextStep;
            state.nextStep++;
            String animId = step.animId();
            AnimDefinition animation = ContentStore.get().animation(animId);
            boolean ok = false;
            if (animation != null && !ContentStore.get().isDisabled(animId)) {
                int duration = stepDuration(definition, index);
                // 步骤的 loop 字段优先于动作自身定义（与 PlayPayload.loop 的客户端语义一致）。
                ok = AnimServerManager.get().play(player, animId, duration, "cutscene", step.loop()) > 0;
            }
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-step player={} id={} at={} kind=anim ref={} result={}",
                    state.playerName, definition.id(), step.at(), animId, ok ? "ok" : "skip");
        }
    }

    /**
     * 动作步骤的时长：非循环步骤用动作默认时长（返回 -1）；
     * 循环步骤播到下一步（或过场结束），保证动作不会提前结束。
     */
    private static int stepDuration(CutsceneDefinition definition, int index) {
        CutsceneStep step = definition.steps().get(index);
        if (!step.loop()) {
            return -1;
        }
        int nextAt = definition.durationTicks();
        List<CutsceneStep> steps = definition.steps();
        for (int i = index + 1; i < steps.size(); i++) {
            int at = steps.get(i).at();
            if (at > step.at()) {
                nextAt = at;
                break;
            }
        }
        return Math.max(1, nextAt - step.at());
    }

    /** 发结束包 + 记 cutscene-end（+ 提前结束时收镜头）。 */
    private void finish(ServerPlayer player, ActiveCutscene state, String reason) {
        CutsceneDefinition definition = state.definition;
        ServerNetworking.sendCutscene(player, new ModPayloads.CutscenePayload(
                definition.id(), definition.titleKey(), definition.durationTicks(), false,
                cameraPayloadStyle(definition)));
        if (state.cameraStarted && !"finished".equals(reason)) {
            RpgAnimInterop.stopCamera(player);
        }
        RpgAnim.LOGGER.info("[RpgAnim] cutscene-end player={} id={} reason={}",
                state.playerName, definition.id(), reason);
    }

    private void onServerStopping(MinecraftServer server) {
        for (Map.Entry<UUID, ActiveCutscene> entry : active.entrySet()) {
            ActiveCutscene state = entry.getValue();
            if (state.cameraStarted) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    RpgAnimInterop.stopCamera(player);
                }
            }
            RpgAnim.LOGGER.info("[RpgAnim] cutscene-end player={} id={} reason=quit",
                    state.playerName, state.definition.id());
        }
        active.clear();
        demos.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static String cameraPayloadStyle(CutsceneDefinition definition) {
        return definition.hasCamera() ? definition.camera().style() : "";
    }

    private static long now(ServerPlayer player) {
        return player.level().getServer().getTickCount();
    }

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
