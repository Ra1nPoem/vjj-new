package com.vjj.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * VJJ-NEW 网络注册与发送工具（common 侧）。
 *
 * <p><b>26.3 坑</b>：S2C payload 类型必须在客户端与服务端<b>都</b>注册，否则包被静默丢弃
 * （见工作区 notes/api-server.md）；因此 {@link #registerCommon()} 在 common 入口调用一次即可
 * （客户端也会执行 common 入口）。客户端接收器在 {@code com.vjj.client.VjjClientNet} 注册。</p>
 */
public final class VjjNet {
    private VjjNet() {
    }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.EditorOpenPayload.TYPE,
                VjjPayloads.EditorOpenPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.EditorDataPayload.TYPE,
                VjjPayloads.EditorDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.ClipPlayPayload.TYPE,
                VjjPayloads.ClipPlayPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.ClipStopPayload.TYPE,
                VjjPayloads.ClipStopPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.ZoomPayload.TYPE,
                VjjPayloads.ZoomPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VjjPayloads.LockPayload.TYPE,
                VjjPayloads.LockPayload.CODEC);
        // 特效库（1.1.0 新增）：服务端广播视觉特效参数，客户端用粒子/屏幕层渲染。
        PayloadTypeRegistry.clientboundPlay().register(com.vjj.fx.FxPayload.TYPE, com.vjj.fx.FxPayload.CODEC);
        // 字幕（1.1.0 新增）：UI 字幕 + 清空；世界字幕走实体同步、不需要额外 payload。
        PayloadTypeRegistry.clientboundPlay().register(
                com.vjj.subtitle.SubtitleUiPayload.TYPE, com.vjj.subtitle.SubtitleUiPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                com.vjj.subtitle.SubtitleClearUiPayload.TYPE, com.vjj.subtitle.SubtitleClearUiPayload.CODEC);

        PayloadTypeRegistry.serverboundPlay().register(VjjPayloads.EditorActionPayload.TYPE,
                VjjPayloads.EditorActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(VjjPayloads.UiEventPayload.TYPE,
                VjjPayloads.UiEventPayload.CODEC);

        // C2S 全局接收器：Fabric 只认“服务端注册了 receiver”的 C2S 类型，否则客户端 canSend=false、
        // 面板按钮会被静默丢弃（E2E 曾以 `ui-action ... reason=no-channel` 抓到）。common 入口只调用一次。
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                VjjPayloads.EditorActionPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.level.ServerPlayer player = context.player();
                    context.server().execute(() -> com.vjj.editor.VjjEditorService.handleAction(player, payload));
                });
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                VjjPayloads.UiEventPayload.TYPE, (payload, context) -> {
                    net.minecraft.server.level.ServerPlayer player = context.player();
                    context.server().execute(() -> com.vjj.editor.VjjEditorService.handleUiEvent(player, payload));
                });
    }

    /** 发给单个玩家（离线/通道不可用返回 false 并记日志）。 */
    public static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null) {
            return false;
        }
        if (!ServerPlayNetworking.canSend(player, payload.type())) {
            return false;
        }
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    /** 发给服务器上所有玩家（广播镜头/演示用）。 */
    public static int broadcastAll(MinecraftServer server, CustomPacketPayload payload) {
        int sent = 0;
        if (server == null) {
            return 0;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (send(player, payload)) {
                sent++;
            }
        }
        return sent;
    }
}
