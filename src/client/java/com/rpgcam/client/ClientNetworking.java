package com.rpgcam.client;

import com.rpgcam.RpgCamera;
import com.rpgcam.net.ModPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端网络：接收 S2C cue/stop，回执 C2S done（服务端日志取证用）。
 */
public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.CuePayload.TYPE, (payload, context) ->
                context.client().execute(() -> CameraDirector.get().receiveCue(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.StopPayload.TYPE, (payload, context) ->
                context.client().execute(() -> CameraDirector.get().receiveStop(payload)));

        RpgCamera.LOGGER.info("[RpgCamera] client receivers registered");
    }

    public static void sendDone(String styleId, int ticksPlayed, boolean interrupted) {
        if (ClientPlayNetworking.canSend(ModPayloads.DonePayload.TYPE)) {
            ClientPlayNetworking.send(new ModPayloads.DonePayload(styleId, ticksPlayed, interrupted, "restored"));
        }
    }
}
