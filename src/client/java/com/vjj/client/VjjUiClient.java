package com.vjj.client;

import com.vjj.client.state.VjjEditorState;
import com.vjj.client.ui.AnimBoardScreen;
import com.vjj.client.ui.CameraEditorScreen;
import com.vjj.client.ui.VjjHubScreen;
import com.vjj.client.ui.VjjSettingsScreen;
import com.vjj.net.VjjPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可视化面板的客户端门面：发动作 / 发 UI 取证事件 / 打开界面 / 接收数据快照。
 *
 * <p>所有界面共用：（1）读取 {@link VjjEditorState}；（2）点击时调用本类的方法发 C2S；
 * （3）服务端处理后回 {@code EditorDataPayload}，界面下一帧自动重绘。</p>
 */
public final class VjjUiClient {
    public static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");

    private VjjUiClient() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 打开 / 数据
    // ─────────────────────────────────────────────────────────────────────

    /** 服务端发来“打开面板”。 */
    public static void onEditorOpen(VjjPayloads.EditorOpenPayload payload) {
        VjjEditorState.get().setSession(payload.sessionId(), payload.op());
        String tab = payload.tab() == null ? "hub" : payload.tab();
        LOGGER.info("[VjjNew] editor-open tab={} session={} op={}", tab, payload.sessionId(), payload.op());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> {
            Screen screen = switch (tab) {
                case "camera" -> new CameraEditorScreen();
                case "anim" -> new AnimBoardScreen();
                case "settings" -> new VjjSettingsScreen();
                case "fx" -> new com.vjj.client.ui.FxLabScreen();
                default -> new VjjHubScreen();
            };
            minecraft.gui.setScreen(screen);
            LOGGER.info("[VjjNew] ui-open screen={} tab={}", screen.getClass().getSimpleName(), tab);
        });
    }

    /** 服务端下发数据快照。 */
    public static void onEditorData(VjjPayloads.EditorDataPayload payload) {
        VjjEditorState.get().apply(payload);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 发送动作
    // ─────────────────────────────────────────────────────────────────────

    /** 通用动作发送（GUI 所有按钮最终都走这里）。 */
    public static void sendAction(String action, String clipId, int index, int tick,
                                  double x, double y, double z, double yaw, double pitch, double fov,
                                  String s1, String s2) {
        VjjEditorState state = VjjEditorState.get();
        if (state.sessionId() <= 0) {
            LOGGER.info("[VjjNew] ui-action action={} result=skipped reason=no-session", action);
            return;
        }
        if (!ClientPlayNetworking.canSend(VjjPayloads.EditorActionPayload.TYPE)) {
            LOGGER.info("[VjjNew] ui-action action={} result=skipped reason=no-channel", action);
            return;
        }
        ClientPlayNetworking.send(new VjjPayloads.EditorActionPayload(state.sessionId(),
                action, clipId == null ? "" : clipId, index, tick, x, y, z, yaw, pitch, fov,
                s1 == null ? "" : s1, s2 == null ? "" : s2));
        LOGGER.info("[VjjNew] ui-action-send action={} clip={} index={} tick={}", action,
                clipId == null ? "" : clipId, index, tick);
    }

    public static void send(String action) {
        sendAction(action, "", -1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "", "");
    }

    public static void send(String action, String clipId) {
        sendAction(action, clipId, -1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "", "");
    }

    /** 把本机当前机位存成一个关键帧（相机编辑器“捕获当前机位”按钮）。 */
    public static void sendCapturePose(String clipId, int tick, double x, double y, double z,
                                       double yaw, double pitch, double fov) {
        sendAction("key_add", clipId, -1, tick, x, y, z, yaw, pitch, fov, "gui-capture", "");
    }

    /** 动画板块：播放动作 / 假人 / 目标循环。 */
    public static void sendAnimPlay(String animId, boolean loop) {
        sendAction("anim_play", "", -1, loop ? 1 : 0, 0, 0, 0, 0, 0, 0, animId, "");
    }

    public static void sendDummySpawn(String animId) {
        sendAction("dummy_spawn", "", -1, 0, 0, 0, 0, 0, 0, 0, animId, "");
    }

    public static void sendZoomTest(double factor) {
        sendAction("zoom_test", "", -1, 80, 0, 0, 0, 0, 0, factor, "", "");
    }

    /** UI 取证事件（服务端只记日志，不改变状态）。 */
    public static void sendUiEvent(String event, String detail) {
        if (!ClientPlayNetworking.canSend(VjjPayloads.UiEventPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new VjjPayloads.UiEventPayload(event, detail));
    }

    /** 当前打开的界面类名（日志/诊断用）。 */
    public static String currentScreenId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui.screen() == null) {
            return "none";
        }
        return minecraft.gui.screen().getClass().getSimpleName();
    }
}
