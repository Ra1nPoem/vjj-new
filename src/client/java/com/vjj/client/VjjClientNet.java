package com.vjj.client;

import com.vjj.client.camera.VjjCameraClient;
import com.vjj.client.camera.VjjInputLock;
import com.vjj.client.camera.VjjZoomClient;
import com.vjj.net.VjjPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * 客户端网络接收器：所有 S2C 包在这里收口，再分发给各模块。
 *
 * <p>26.3 坑：网络回调不在主线程，必须 {@code context.client().execute(...)} 切回主线程，
 * 否则 {@code mc.gui.setScreen} 会报 “called from non-game thread” 并静默不生效。</p>
 */
public final class VjjClientNet {
    private VjjClientNet() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.EditorOpenPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjUiClient.onEditorOpen(payload)));
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.EditorDataPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjUiClient.onEditorData(payload)));
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.ClipPlayPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjCameraClient.onClipPlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.ClipStopPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjCameraClient.onClipStop(payload)));
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.ZoomPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjZoomClient.onZoom(payload)));
        ClientPlayNetworking.registerGlobalReceiver(VjjPayloads.LockPayload.TYPE,
                (payload, context) -> context.client().execute(() -> VjjInputLock.onLock(payload)));
        // 特效库：服务端广播的特效参数（位置/颜色/缩放/时长）
        ClientPlayNetworking.registerGlobalReceiver(com.vjj.fx.FxPayload.TYPE,
                (payload, context) -> context.client().execute(() -> com.vjj.client.fx.FxManager.onPayload(payload)));
        // 字幕：UI 台词（每句位置/样式独立）与清空
        ClientPlayNetworking.registerGlobalReceiver(com.vjj.subtitle.SubtitleUiPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        com.vjj.client.subtitle.VjjSubtitleHud.show(payload.text(), payload.xFrac(), payload.yFrac(),
                                payload.durationTicks(), payload.color(), payload.scale(), payload.shadow())));
        ClientPlayNetworking.registerGlobalReceiver(com.vjj.subtitle.SubtitleClearUiPayload.TYPE,
                (payload, context) -> context.client().execute(
                        com.vjj.client.subtitle.VjjSubtitleHud::clear));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> reset());
        VjjUiClient.LOGGER.info("[VjjNew] client receivers registered payloads=6");
    }

    public static void reset() {
        com.vjj.client.state.VjjEditorState.get().reset();
        VjjCameraClient.reset();
        VjjZoomClient.reset();
        VjjInputLock.reset();
        com.vjj.client.subtitle.VjjSubtitleHud.clear();
    }
}
