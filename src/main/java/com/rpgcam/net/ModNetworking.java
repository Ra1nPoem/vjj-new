package com.rpgcam.net;

import com.rpgcam.RpgCamera;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络层：payload 注册 + 服务端发包薄封装。
 * 26.3 的 fabric-api 命名：{@code clientboundPlay()} / {@code serverboundPlay()}。
 */
public final class ModNetworking {
    private ModNetworking() {
    }

    /** 双端都要调用（ModInitializer 里），否则 S2C 包会被静默丢弃。 */
    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.CuePayload.TYPE, ModPayloads.CuePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.StopPayload.TYPE, ModPayloads.StopPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.DonePayload.TYPE, ModPayloads.DonePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.DonePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> RpgCamera.LOGGER.info(
                    "[RpgCamera] cue-client-done player={} style={} ticks={} interrupted={} details={}",
                    player.getGameProfile().name(), payload.styleId(), payload.ticksPlayed(),
                    payload.interrupted(), payload.details()));
        });

        RpgCamera.LOGGER.info("[RpgCamera] network payloads registered");
    }

    public static boolean sendCue(ServerPlayer player, ModPayloads.CuePayload payload) {
        if (!ServerPlayNetworking.canSend(player, ModPayloads.CuePayload.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    public static boolean sendStop(ServerPlayer player, ModPayloads.StopPayload payload) {
        if (!ServerPlayNetworking.canSend(player, ModPayloads.StopPayload.TYPE)) {
            return false;
        }
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    public static boolean canSendCue(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, ModPayloads.CuePayload.TYPE);
    }
}
