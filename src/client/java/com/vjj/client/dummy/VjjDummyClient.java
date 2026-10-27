package com.vjj.client.dummy;

import com.rpganim.client.ClientAnimState;
import com.vjj.client.VjjUiClient;
import com.vjj.dummy.ModEntities;
import com.vjj.dummy.VjjDummyEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端假人模块：渲染器注册 + 假人动画驱动 + 皮肤解析。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>{@link #register()}：注册 {@code vjjnew:dummy} 的 {@link VjjDummyRenderer}（玩家模型 + 目标皮肤）；</li>
 *   <li>{@link #tick(Minecraft)}：每个客户端 tick 扫描客户端世界里的假人实体，
 *       按同步来的 {@code animId + animSeq}（seq 变化即重播）调用
 *       {@link ClientAnimState#startExternalAnim(int, String, int, boolean, String)}；</li>
 *   <li>{@link #skinFor(UUID)}：owner UUID → {@link PlayerSkin}（优先在线玩家真实皮肤，回落默认皮肤，按 UUID 缓存）；</li>
 *   <li>{@link #onDummyRendered(VjjDummyEntity)}：首次渲染日志 {@code [VjjNew] dummy-render entity=<id> anim=<id>}。</li>
 * </ol>
 *
 * <p>动画日志契约（E2E grep）：</p>
 * <pre>
 * [VjjNew] dummy-anim entity=&lt;id&gt; anim=&lt;id&gt; duration=&lt;n&gt; loop=&lt;b&gt;
 * [VjjNew] dummy-render entity=&lt;id&gt; anim=&lt;id&gt;
 * </pre>
 * <p>内容（rpganim content sync）还没到时启动会失败：只打一条
 * {@code [VjjNew] dummy-anim-skip ... reason=no-content} 并每 tick 重试，sync 到达后自动开始播放，
 * 期间假人静止（渲染器照常显示玩家皮肤）。</p>
 */
public final class VjjDummyClient {
    private static final org.slf4j.Logger LOGGER = VjjUiClient.LOGGER;

    /** entityId → 同步来的动作状态（用于检测 seq 变化 / 内容到达后重试）。 */
    private static final Map<Integer, AnimState> ANIMS = new HashMap<>();
    /** UUID → 解析后的皮肤（断线清空）。 */
    private static final Map<UUID, PlayerSkin> SKINS = new HashMap<>();
    /** 已打过 dummy-render 的实体 id（每个实体只打一条，E2E 用）。 */
    private static final Set<Integer> RENDER_LOGGED = new HashSet<>();
    private static boolean registered;

    private VjjDummyClient() {
    }

    /** 假人动作状态快照（{@code started=false} 表示内容未到达，等待重试）。 */
    private record AnimState(String animId, int seq, boolean loop, int duration, boolean started) {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 注册
    // ─────────────────────────────────────────────────────────────────────

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // 26.3：EntityRendererRegistry（Fabric API）——渲染器用原版 PlayerModel
        EntityRendererRegistry.register(ModEntities.DUMMY, VjjDummyRenderer::new);
        LOGGER.info("[VjjNew] dummy renderer registered id={} model=player",
                ModEntities.DUMMY_KEY.identifier());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 每客户端 tick：扫描假人实体，驱动客户端本地动作时钟
    // ─────────────────────────────────────────────────────────────────────

    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) {
            if (!ANIMS.isEmpty() || !RENDER_LOGGED.isEmpty()) {
                reset();
            }
            return;
        }
        ClientLevel level = minecraft.level;
        Set<Integer> seen = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof VjjDummyEntity dummy)) {
                continue;
            }
            int entityId = dummy.getId();
            seen.add(entityId);
            syncDummy(entityId, dummy.getAnimId(), dummy.getAnimSeq(), dummy.isLoop(),
                    dummy.getAnimDuration());
        }
        // 实体已消失：停掉本地动作时钟（客户端本地时钟不会自己收到 Stop 包）
        Iterator<Map.Entry<Integer, AnimState>> iterator = ANIMS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, AnimState> entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                iterator.remove();
                RENDER_LOGGED.remove(entry.getKey());
                ClientAnimState.stopExternalAnim(entry.getKey(), "removed");
            }
        }
        retryPending();
    }

    /** 同步数据变化（首次出现 / seq 变化 / 动作切换）时重启客户端动作时钟。 */
    private static void syncDummy(int entityId, String animId, int seq, boolean loop, int duration) {
        String effectiveAnim = animId == null ? "" : animId;
        AnimState cached = ANIMS.get(entityId);
        if (cached != null && cached.seq() == seq && cached.animId().equals(effectiveAnim)) {
            return;
        }
        AnimState next = new AnimState(effectiveAnim, seq, loop, duration, false);
        ANIMS.put(entityId, next);
        if (effectiveAnim.isBlank() || seq == 0) {
            ClientAnimState.stopExternalAnim(entityId, "cleared");
            return;
        }
        ANIMS.put(entityId, startAnim(entityId, next, true));
    }

    /** 内容 sync 晚于假人出现：每 tick 重试一次，成功后自动开始播放。 */
    private static void retryPending() {
        if (!ClientAnimState.hasContent() || ANIMS.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, AnimState> entry : ANIMS.entrySet()) {
            AnimState state = entry.getValue();
            if (state.started() || state.animId().isBlank()) {
                continue;
            }
            AnimState applied = startAnim(entry.getKey(), state, false);
            if (applied.started()) {
                entry.setValue(applied);
            }
        }
    }

    /**
     * 尝试启动一个假人的动作时钟。
     *
     * @param logSkip 失败时是否打 dummy-anim-skip（重试路径不重复打日志）
     */
    private static AnimState startAnim(int entityId, AnimState state, boolean logSkip) {
        int applied = ClientAnimState.startExternalAnim(entityId, state.animId(), state.duration(),
                state.loop(), "dummy");
        if (applied > 0) {
            LOGGER.info("[VjjNew] dummy-anim entity={} anim={} duration={} loop={}",
                    entityId, state.animId(), applied, state.loop());
            return new AnimState(state.animId(), state.seq(), state.loop(), state.duration(), true);
        }
        if (logSkip) {
            String reason = ClientAnimState.hasContent() ? "unknown-anim" : "no-content";
            LOGGER.info("[VjjNew] dummy-anim-skip entity={} anim={} reason={}",
                    entityId, state.animId(), reason);
        }
        return state;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 皮肤解析（目标玩家 UUID → PlayerSkin）
    // ─────────────────────────────────────────────────────────────────────

    /** owner UUID → 皮肤：优先在线玩家真实皮肤（含 slim 模型标记），回落 UUID 默认皮肤；按 UUID 缓存。 */
    public static PlayerSkin skinFor(UUID uuid) {
        if (uuid == null) {
            return defaultSkin();
        }
        PlayerSkin cached = SKINS.get(uuid);
        if (cached != null) {
            return cached;
        }
        PlayerSkin resolved = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(uuid);
            if (info != null) {
                resolved = info.getSkin();
            }
        }
        if (resolved == null) {
            try {
                resolved = DefaultPlayerSkin.get(uuid);
            } catch (RuntimeException exception) {
                resolved = null;
            }
        }
        if (resolved == null) {
            resolved = defaultSkin();
        }
        if (resolved != null) {
            SKINS.put(uuid, resolved);
        }
        return resolved;
    }

    private static PlayerSkin defaultSkin() {
        try {
            return DefaultPlayerSkin.getDefaultSkin();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 渲染取证 + 断线清理
    // ─────────────────────────────────────────────────────────────────────

    /** 首次渲染某个假人时打一条 {@code [VjjNew] dummy-render entity=<id> anim=<id>}。 */
    public static void onDummyRendered(VjjDummyEntity dummy) {
        if (dummy == null) {
            return;
        }
        int entityId = dummy.getId();
        if (RENDER_LOGGED.add(entityId)) {
            LOGGER.info("[VjjNew] dummy-render entity={} anim={}", entityId, dummy.getAnimId());
        }
    }

    /** 断线/离开世界：清空本地时钟与皮肤缓存（entityId/UUID 会重新分配）。 */
    public static void reset() {
        int count = ANIMS.size();
        for (int entityId : ANIMS.keySet()) {
            ClientAnimState.stopExternalAnim(entityId, "world-leave");
        }
        ANIMS.clear();
        SKINS.clear();
        RENDER_LOGGED.clear();
        if (count > 0) {
            LOGGER.info("[VjjNew] dummy client reset dummies={}", count);
        }
    }
}
