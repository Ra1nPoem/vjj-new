package com.vjj.editor;

import com.google.gson.Gson;
import com.vjj.VjjNew;
import com.vjj.camera.VjjCameraServer;
import com.vjj.dummy.VjjDummyServer;
import com.vjj.net.VjjNet;
import com.vjj.net.VjjPayloads;
import com.vjj.scene.VjjSceneManager;
import com.vjj.store.VjjConfigStore;
import com.vjj.store.VjjSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 可视化面板的服务端路由：会话管理 + 动作分发 + 数据快照下发。
 *
 * <p>动作命名契约见 docs/VJJ-DESIGN.md §4（GUI 与命令共用同一批动作）。</p>
 *
 * <p>日志契约（E2E 依赖）：</p>
 * <pre>
 * [VjjNew] editor-open player=&lt;name&gt; session=&lt;n&gt; tab=&lt;tab&gt; clips=&lt;n&gt;
 * [VjjNew] ui-action player=&lt;name&gt; session=&lt;n&gt; action=&lt;id&gt; clip=&lt;id&gt; index=&lt;n&gt; result=&lt;ok|unknown|denied&gt; status="&lt;中文状态&gt;"
 * [VjjNew] ui-event player=&lt;name&gt; event=&lt;id&gt; detail="&lt;text&gt;"
 * </pre>
 */
public final class VjjEditorService {
    private static final Gson GSON = new Gson();
    private static final Map<UUID, Integer> SESSIONS = new HashMap<>();
    private static int sessionCounter;

    private VjjEditorService() {
    }

    public static void init() {
        VjjNew.LOGGER.info("[VjjNew] editor service init");
    }

    /** 打开可视化面板（服务端再次校验 OP 权限；非 OP 拒绝并记日志）。 */
    public static boolean openEditor(ServerPlayer op, String tab) {
        if (op == null) {
            return false;
        }
        if (!isOp(op)) {
            VjjNew.LOGGER.info("[VjjNew] editor-open player={} result=denied reason=not-op",
                    op.getGameProfile().name());
            return false;
        }
        int session = ++sessionCounter;
        SESSIONS.put(op.getUUID(), session);
        String effectiveTab = tab == null || tab.isBlank() ? "hub" : tab;
        VjjNet.send(op, new VjjPayloads.EditorOpenPayload(session, true, effectiveTab));
        sendSnapshot(op, "面板已打开（点击各页签编辑，保存后写入 config）");
        VjjNew.LOGGER.info("[VjjNew] editor-open player={} session={} tab={} clips={}",
                op.getGameProfile().name(), session, effectiveTab, VjjConfigStore.get().clips().size());
        return true;
    }

    /** 客户端点击动作入口（C2S）。 */
    public static void handleAction(ServerPlayer op, VjjPayloads.EditorActionPayload action) {
        if (op == null || action == null) {
            return;
        }
        String name = op.getGameProfile().name();
        if (!isOp(op)) {
            VjjNew.LOGGER.info("[VjjNew] ui-action player={} action={} result=denied reason=not-op", name,
                    action.action());
            return;
        }
        Integer session = SESSIONS.get(op.getUUID());
        if (session == null || session != action.sessionId()) {
            VjjNew.LOGGER.info("[VjjNew] ui-action player={} session={} action={} result=denied reason=stale-session",
                    name, action.sessionId(), action.action());
            return;
        }
        String status = route(op, action);
        boolean handled = status != null;
        if (status == null) {
            status = "未知动作: " + action.action();
        }
        VjjNew.LOGGER.info("[VjjNew] ui-action player={} session={} action={} clip={} index={} result={} status=\"{}\"",
                name, action.sessionId(), action.action(), action.clipId(), action.index(),
                handled ? "ok" : "unknown", status);
        sendSnapshot(op, status);
    }

    /** UI 取证事件（客户端上报，服务端只记日志）。 */
    public static void handleUiEvent(ServerPlayer op, VjjPayloads.UiEventPayload payload) {
        if (op == null || payload == null) {
            return;
        }
        VjjNew.LOGGER.info("[VjjNew] ui-event player={} event={} detail=\"{}\"",
                op.getGameProfile().name(), payload.event(), payload.detail());
    }

    /** 下发一次完整数据快照（clips/settings/anims/targets/status）。 */
    public static void sendSnapshot(ServerPlayer op, String status) {
        if (op == null) {
            return;
        }
        Integer session = SESSIONS.get(op.getUUID());
        if (session == null) {
            return;
        }
        VjjConfigStore store = VjjConfigStore.get();
        String animsJson = GSON.toJson(VjjDummyServer.animationIds());
        String targetsJson = GSON.toJson(targetNames(op.level().getServer()));
        VjjNet.send(op, new VjjPayloads.EditorDataPayload(session,
                store.clipsJson(), store.settingsJson(), animsJson, targetsJson,
                status == null ? "" : status));
    }

    /** 会话失效（掉线时清）。 */
    public static void onPlayerQuit(ServerPlayer player) {
        if (player != null) {
            SESSIONS.remove(player.getUUID());
        }
    }

    public static List<String> targetNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                names.add(player.getGameProfile().name());
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 动作分发
    // ─────────────────────────────────────────────────────────────────────

    private static String route(ServerPlayer op, VjjPayloads.EditorActionPayload action) {
        String actionId = action.action() == null ? "" : action.action();
        VjjConfigStore store = VjjConfigStore.get();
        VjjSettings settings = store.settings();
        switch (actionId) {
            case "save": {
                boolean ok = store.save();
                return ok ? "已保存到 config/vjjnew/config.json（" + store.clips().size() + " 段镜头）"
                        : "保存失败，见服务端日志";
            }
            case "reload": {
                store.load();
                VjjSettings reloaded = store.settings();
                VjjNew.LOGGER.info("[VjjNew] editor-reload player={} clips={} settings={}",
                        op.getGameProfile().name(), store.clips().size(), reloaded);
                return "已重新载入配置（" + store.clips().size() + " 段镜头）";
            }
            case "set_force": {
                settings.forceCamera = !settings.forceCamera;
                return "强制玩家镜头 = " + settings.forceCamera;
            }
            case "set_lock": {
                settings.lockAction = !settings.lockAction;
                return "锁死玩家行动 = " + settings.lockAction;
            }
            case "set_hide": {
                settings.hidePlayer = !settings.hidePlayer;
                return "隐藏玩家本体 = " + settings.hidePlayer;
            }
            case "set_dummy": {
                settings.spawnDummy = !settings.spawnDummy;
                return "播放动画时用假人替代 = " + settings.spawnDummy;
            }
            case "set_broadcast": {
                settings.broadcastCamera = !settings.broadcastCamera;
                return "镜头广播给所有玩家 = " + settings.broadcastCamera;
            }
            case "set_zoom_enabled": {
                settings.zoomEnabled = !settings.zoomEnabled;
                return "变焦开关 = " + settings.zoomEnabled;
            }
            case "set_zoom": {
                double factor = action.fov() > 0.0 ? action.fov() : settings.zoomFactor;
                settings.zoomFactor = Math.clamp(factor, 1.0, 20.0);
                return "变焦倍率 = " + settings.zoomFactor;
            }
            case "scene_play": {
                String clipId = !action.clipId().isBlank() ? action.clipId() : action.s1();
                String anim = action.s2() == null || action.s2().isBlank() ? settings.defaultAnim : action.s2();
                boolean ok = VjjSceneManager.play(op, clipId, anim, settings.targetMode);
                return ok ? "场景开始: " + clipId + "（假人动画 " + anim + "）" : "场景开始失败: " + clipId;
            }
            case "scene_stop": {
                boolean ok = VjjSceneManager.stop(op, "gui");
                return ok ? "场景已停止" : "当前没有场景在播放";
            }
            case "zoom_test": {
                double factor = action.fov() > 0.0 ? action.fov() : settings.zoomFactor;
                VjjCameraServer.requestZoom(op, factor, action.tick() > 0 ? action.tick() : 80, "gui-test");
                return "已请求变焦 " + factor + "x（" + (action.tick() > 0 ? action.tick() : 80) + "t）";
            }
            case "set_emf_bridge": {
                settings.emfBridge = !settings.emfBridge;
                return "EMF 动画管线兼容 = " + settings.emfBridge
                        + (settings.emfBridge ? "（VJJ 动作叠加在资源包/T&M 姿态之上）" : "（退回原生 mixin 叠加）");
            }
            case "set_pose_priority": {
                settings.externalPosePriority = !settings.externalPosePriority;
                return "VJJ 动作骨骼优先于外部姿态 = " + settings.externalPosePriority;
            }
            case "set_overlay_weight": {
                double weight = Math.clamp(action.fov() > 0.0 ? action.fov() : settings.overlayWeight, 0.2, 1.0);
                settings.overlayWeight = weight;
                return "外部动画环境叠加权重 = " + String.format(java.util.Locale.ROOT, "%.2f", weight);
            }
            default: {
                String camera = VjjCameraServer.handleAction(op, action);
                if (camera != null) {
                    return camera;
                }
                String dummy = VjjDummyServer.handleAction(op, action);
                if (dummy != null) {
                    return dummy;
                }
                return null;
            }
        }
    }

    private static boolean isOp(ServerPlayer player) {
        // 26.3 权限 API：ServerPlayer#permissions() 返回 PermissionSet；与命令同一个判定
        return net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }
}
