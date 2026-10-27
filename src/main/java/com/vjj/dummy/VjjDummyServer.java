package com.vjj.dummy;

import com.rpganim.api.RpgAnimApi;
import com.rpganim.server.ContentStore;
import com.vjj.VjjNew;
import com.vjj.net.VjjPayloads;
import com.vjj.store.VjjConfigStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家假人服务端模块：生成/清理/切换“玩家假人”，并处理动画板块（GUI）的动作。
 *
 * <p>假人 = {@code vjjnew:dummy}（{@link VjjDummyEntity}）：玩家外观 + 目标皮肤 + rpganim 骨骼动作。
 * 本体隐藏 / 锁行动由 {@code com.vjj.scene.VjjSceneManager} 编排（本模块只负责假人本身）。</p>
 *
 * <p>数据模型：{@code ownerUuid → List<entityId>}（一个玩家可同时拥有多个假人，场景通常 1 个）。
 * 服务端 tick 兜底清理已消失/换维/owner 离线的假人；每 200 tick 扫描一次孤儿实体
 * （不在登记表里的假人，例如历史存档/崩溃残留）。</p>
 *
 * <p>日志契约（E2E 依赖，格式不要改）：</p>
 * <pre>
 * [VjjNew] dummy-spawn player=&lt;name&gt; dummy=&lt;id&gt; anim=&lt;id&gt; pos=&lt;x,y,z&gt; result=ok
 * [VjjNew] dummy-clear player=&lt;name&gt; removed=&lt;n&gt;
 * [VjjNew] dummy-set-anim dummy=&lt;id&gt; anim=&lt;id&gt; loop=&lt;b&gt;
 * </pre>
 *
 * <p>该文件的方法签名由 docs/VJJ-DESIGN.md §3.2 固定，被 {@code VjjNew} / {@code VjjSceneManager} /
 * {@code VjjEditorService} / {@code VjjCommands} 调用。</p>
 */
public final class VjjDummyServer {
    /** UUID 分隔的假人登记（LinkedHashMap：保持生成顺序，便于「最近一个」语义）。 */
    private static final Map<UUID, List<Integer>> OWNED = new LinkedHashMap<>();
    /** entityId → 实体引用（服务端主线程访问）。 */
    private static final Map<Integer, VjjDummyEntity> LIVE = new LinkedHashMap<>();
    /** 孤儿扫描间隔（tick）。 */
    private static final int ORPHAN_SCAN_INTERVAL = 200;
    private static boolean initialized;

    private VjjDummyServer() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────────────

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ModEntities.init();
        VjjNew.LOGGER.info("[VjjNew] dummy server init entity={}", ModEntities.DUMMY_KEY.identifier());
    }

    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        // 1) 清理已消失 / owner 离线 / 换维度的假人（owner 跟随由实体自己的 tick 做）
        for (Map.Entry<UUID, List<Integer>> entry : new ArrayList<>(OWNED.entrySet())) {
            UUID ownerId = entry.getKey();
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            List<Integer> ids = entry.getValue();
            Iterator<Integer> iterator = ids.iterator();
            while (iterator.hasNext()) {
                int entityId = iterator.next();
                VjjDummyEntity dummy = LIVE.get(entityId);
                if (dummy == null || dummy.isRemoved() || owner == null || dummy.level() != owner.level()) {
                    iterator.remove();
                    LIVE.remove(entityId);
                    if (dummy != null && !dummy.isRemoved()) {
                        dummy.discard();
                    }
                }
            }
            if (ids.isEmpty()) {
                OWNED.remove(ownerId);
            }
        }
        // 2) 兜底：清理不在登记表里的假人（存档残留 / 异常路径）——假人只应随场景存活，绝不长期留场
        if (server.getTickCount() % ORPHAN_SCAN_INTERVAL == 0) {
            List<VjjDummyEntity> orphans = new ArrayList<>();
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof VjjDummyEntity dummy && !LIVE.containsKey(dummy.getId())) {
                        orphans.add(dummy);
                    }
                }
            }
            for (VjjDummyEntity orphan : orphans) {
                orphan.discard();
                VjjNew.LOGGER.info("[VjjNew] dummy-cleanup dummy={} reason=orphan", orphan.getId());
            }
        }
    }

    public static void onPlayerQuit(ServerPlayer player) {
        if (player != null && OWNED.containsKey(player.getUUID())) {
            clearScene(player);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server == null) {
            while (!OWNED.isEmpty()) {
                UUID ownerId = OWNED.keySet().iterator().next();
                List<Integer> ids = OWNED.remove(ownerId);
                if (ids == null) {
                    continue;
                }
                int removed = 0;
                for (int entityId : ids) {
                    VjjDummyEntity dummy = LIVE.remove(entityId);
                    if (dummy != null && !dummy.isRemoved()) {
                        dummy.discard();
                        removed++;
                    }
                }
                VjjNew.LOGGER.info("[VjjNew] dummy-clear player={} removed={}", "-", removed);
            }
            LIVE.clear();
            return;
        }
        for (UUID ownerId : new ArrayList<>(OWNED.keySet())) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) {
                clearScene(owner);
            } else {
                List<Integer> ids = OWNED.remove(ownerId);
                int removed = 0;
                if (ids != null) {
                    for (int entityId : ids) {
                        VjjDummyEntity dummy = LIVE.remove(entityId);
                        if (dummy != null && !dummy.isRemoved()) {
                            dummy.discard();
                            removed++;
                        }
                    }
                }
                VjjNew.LOGGER.info("[VjjNew] dummy-clear player={} removed={}", "-", removed);
            }
        }
        OWNED.clear();
        LIVE.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生成 / 清理
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 在 actor 当前位置/朝向生成一个替代假人（服务端权威），并设置要播放的动作。
     * 若该 actor 已有假人，先清掉（同一玩家只保留一代假人，避免叠在同一坐标）。
     *
     * @param animId 动画 id（空/null 用 {@code settings.defaultAnim}）
     * @param loop   是否循环
     * @return 实体 id；失败 -1
     */
    public static int spawnForScene(ServerPlayer actor, String animId, boolean loop) {
        if (actor == null) {
            return -1;
        }
        String name = actor.getGameProfile().name();
        ServerLevel level = actor.level();
        if (level == null) {
            VjjNew.LOGGER.info("[VjjNew] dummy-spawn player={} anim={} pos=- result=-1 reason=no-level",
                    name, animId);
            return -1;
        }
        String effectiveAnim = resolveAnim(animId);
        if (effectiveAnim.isBlank()) {
            VjjNew.LOGGER.info(
                    "[VjjNew] dummy-spawn player={} anim={} pos=- result=-1 reason=no-animation",
                    name, animId);
            return -1;
        }
        int existing = countFor(actor);
        if (existing > 0) {
            clearScene(actor);
        }
        VjjDummyEntity dummy = ModEntities.DUMMY.create(level, EntitySpawnReason.COMMAND);
        if (dummy == null) {
            VjjNew.LOGGER.info(
                    "[VjjNew] dummy-spawn player={} anim={} pos=- result=-1 reason=create-failed",
                    name, effectiveAnim);
            return -1;
        }
        double x = actor.getX();
        double y = actor.getY();
        double z = actor.getZ();
        dummy.snapTo(x, y, z, actor.getYRot(), actor.getXRot());
        dummy.setOwner(actor);
        dummy.applyAnim(effectiveAnim, loop, 0);
        if (!level.addFreshEntity(dummy)) {
            VjjNew.LOGGER.info(
                    "[VjjNew] dummy-spawn player={} dummy={} anim={} pos={} result=-1 reason=add-failed",
                    name, dummy.getId(), effectiveAnim, formatPos(x, y, z));
            return -1;
        }
        int entityId = dummy.getId();
        OWNED.computeIfAbsent(actor.getUUID(), key -> new ArrayList<>()).add(entityId);
        LIVE.put(entityId, dummy);
        VjjNew.LOGGER.info("[VjjNew] dummy-spawn player={} dummy={} anim={} pos={} result=ok",
                name, entityId, effectiveAnim, formatPos(x, y, z));
        return entityId;
    }

    /** 清掉 actor 关联的全部假人；返回清掉的数量。 */
    public static int clearScene(ServerPlayer actor) {
        if (actor == null) {
            return 0;
        }
        String name = actor.getGameProfile().name();
        List<Integer> ids = OWNED.remove(actor.getUUID());
        int removed = 0;
        if (ids != null) {
            for (int entityId : ids) {
                VjjDummyEntity dummy = LIVE.remove(entityId);
                if (dummy != null && !dummy.isRemoved()) {
                    dummy.discard();
                    removed++;
                }
            }
        }
        VjjNew.LOGGER.info("[VjjNew] dummy-clear player={} removed={}", name, removed);
        return removed;
    }

    /** actor 当前存活假人数量。 */
    public static int countFor(ServerPlayer actor) {
        if (actor == null) {
            return 0;
        }
        List<Integer> ids = OWNED.get(actor.getUUID());
        if (ids == null) {
            return 0;
        }
        int count = 0;
        for (int entityId : ids) {
            VjjDummyEntity dummy = LIVE.get(entityId);
            if (dummy != null && !dummy.isRemoved()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 让某个假人播放动作；返回是否成功。
     * {@code dummyEntityId <= 0} 表示「最近生成且存活的假人」（{@code /vjj dummy anim} 命令用）。
     */
    public static boolean playAnim(int dummyEntityId, String animId, boolean loop) {
        String effectiveAnim = resolveAnim(animId);
        if (effectiveAnim.isBlank()) {
            return false;
        }
        VjjDummyEntity dummy = dummyEntityId > 0 ? LIVE.get(dummyEntityId) : mostRecentDummy();
        if (dummy == null || dummy.isRemoved()) {
            return false;
        }
        dummy.applyAnim(effectiveAnim, loop, 0);
        VjjNew.LOGGER.info("[VjjNew] dummy-set-anim dummy={} anim={} loop={}",
                dummy.getId(), effectiveAnim, loop);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // GUI 动作（由 VjjEditorService 在 OP/session 校验之后转发）
    // ─────────────────────────────────────────────────────────────────────

    /** 处理动画板块 UI 动作；返回状态行，未处理返回 null。 */
    public static String handleAction(ServerPlayer op, VjjPayloads.EditorActionPayload action) {
        if (op == null || action == null) {
            return null;
        }
        String actionId = action.action() == null ? "" : action.action();
        switch (actionId) {
            case "anim_play" -> {
                ServerPlayer target = resolveTarget(op, action.s2());
                String anim = resolveAnim(action.s1());
                if (anim.isBlank()) {
                    return "没有可播放的动作（rpganim 内容未加载）";
                }
                boolean ok = RpgAnimApi.play(target, anim);
                return ok ? "已播放动作 " + anim + " → " + playerName(target)
                        : "动作播放失败: " + anim + "（内容里没有该动作）";
            }
            case "anim_stop" -> {
                ServerPlayer target = resolveTarget(op, action.s2());
                boolean ok = RpgAnimApi.stop(target);
                return ok ? "已停止 " + playerName(target) + " 的动作" : "没有正在播放的动作";
            }
            case "dummy_spawn" -> {
                ServerPlayer target = resolveTarget(op, action.s2());
                String anim = resolveAnim(action.s1());
                // tick 字段兼作 loop 标志（GUI 传 1 = 循环；命令/场景直接传 loop 参数）
                boolean loop = action.tick() != 0;
                int id = spawnForScene(target, anim, loop);
                return id >= 0
                        ? "假人已生成 id=" + id + " anim=" + anim + " 目标=" + playerName(target)
                        : "假人生成失败（见服务端日志）";
            }
            case "dummy_remove" -> {
                ServerPlayer target = resolveTarget(op, action.s2());
                int removed = clearScene(target);
                return removed > 0 ? "已清除假人 " + removed + " 个（目标=" + playerName(target) + "）"
                        : "目标当前没有假人";
            }
            case "dummy_anim" -> {
                ServerPlayer target = resolveTarget(op, action.s2());
                String anim = resolveAnim(action.s1());
                if (anim.isBlank()) {
                    return "没有可切换的动作（rpganim 内容未加载）";
                }
                List<Integer> ids = OWNED.get(target.getUUID());
                int changed = 0;
                if (ids != null) {
                    for (int entityId : new ArrayList<>(ids)) {
                        if (playAnim(entityId, anim, true)) {
                            changed++;
                        }
                    }
                }
                return changed > 0 ? "已切换 " + changed + " 个假人的动作 → " + anim
                        : "目标没有假人（先点「生成假人」）";
            }
            default -> {
                return null;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询（命令 / GUI / 状态行）
    // ─────────────────────────────────────────────────────────────────────

    public static List<String> listFor(ServerPlayer actor) {
        List<String> lines = new ArrayList<>();
        if (actor == null) {
            return lines;
        }
        List<Integer> ids = OWNED.get(actor.getUUID());
        if (ids == null) {
            return lines;
        }
        for (int entityId : ids) {
            VjjDummyEntity dummy = LIVE.get(entityId);
            if (dummy == null || dummy.isRemoved()) {
                continue;
            }
            lines.add(String.format(Locale.ROOT,
                    "#%d anim=%s seq=%d loop=%b pos=(%.2f,%.2f,%.2f)",
                    entityId, dummy.getAnimId(), dummy.getAnimSeq(), dummy.isLoop(),
                    dummy.getX(), dummy.getY(), dummy.getZ()));
        }
        return lines;
    }

    /** 可播放动画 id 列表（GUI 用；= rpganim 的 14 个动作）。 */
    public static List<String> animationIds() {
        try {
            return new ArrayList<>(ContentStore.get().animationIds());
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────────────────────────────

    private static VjjDummyEntity mostRecentDummy() {
        VjjDummyEntity last = null;
        for (VjjDummyEntity dummy : LIVE.values()) {
            if (dummy != null && !dummy.isRemoved()) {
                last = dummy;
            }
        }
        return last;
    }

    /** 解析目标玩家：空 = op 自己；找不到 = 回落 op（GUI 状态行会显示实际目标名）。 */
    private static ServerPlayer resolveTarget(ServerPlayer op, String name) {
        if (name == null || name.isBlank()) {
            return op;
        }
        MinecraftServer server = op.level().getServer();
        if (server == null) {
            return op;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(name.trim());
        return target != null ? target : op;
    }

    /** 解析动画 id：空 = 设置里的默认动作；再空 = 内容里的第一个。 */
    private static String resolveAnim(String animId) {
        if (animId != null && !animId.isBlank()) {
            return animId.trim();
        }
        String fallback = VjjConfigStore.get().settings().defaultAnim;
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        List<String> ids = animationIds();
        return ids.isEmpty() ? "" : ids.get(0);
    }

    private static String playerName(ServerPlayer player) {
        return player == null ? "-" : player.getGameProfile().name();
    }

    private static String formatPos(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", x, y, z);
    }
}
