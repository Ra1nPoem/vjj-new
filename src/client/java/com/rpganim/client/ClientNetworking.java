package com.rpganim.client;

import com.rpganim.net.ModPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端网络：接收 S2C（sync/play/stop/cutscene），发送 C2S（emote 请求 / done 回执）。
 * <p>payload 类型注册由 common 入口的 {@code NetRegistration} 统一完成（客户端 common init 也会跑），
 * 这里只登记接收器——**不能**再调 {@code PayloadTypeRegistry}，否则重复注册会抛异常。
 * <p>接收回调跑在网络线程，所有状态改动都切回客户端主线程（{@code context.client().execute}），
 * 避免和渲染线程（mixin 采样）竞争。
 */
public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.SyncPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientAnimState.receiveSync(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.PlayPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientAnimState.receivePlay(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.StopPayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientAnimState.receiveStop(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.CutscenePayload.TYPE,
                (payload, context) -> context.client().execute(() -> ClientAnimState.receiveCutscene(payload)));

        RpgAnimClient.LOGGER.info("[RpgAnim] client receivers registered");
    }

    /** 键位 H：请求服务端播放一个 emote 类动作（服务端校验/节流后广播回来）。 */
    public static void sendEmoteRequest(String animId) {
        if (animId == null || animId.isBlank()) {
            return;
        }
        if (!ClientPlayNetworking.canSend(ModPayloads.EmoteRequestPayload.TYPE)) {
            RpgAnimClient.LOGGER.warn("[RpgAnim] emote-request skipped id={} reason=no-server-channel", animId);
            return;
        }
        ClientPlayNetworking.send(new ModPayloads.EmoteRequestPayload(animId));
        RpgAnimClient.LOGGER.info("[RpgAnim] emote-request anim={}", animId);
    }

    /** 动作收尾回执（服务端只记日志，用于 E2E 取证「客户端确实播放过」）。 */
    public static void sendDone(int entityId, String animId, int ticksPlayed, boolean interrupted, String details) {
        if (!ClientPlayNetworking.canSend(ModPayloads.DonePayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new ModPayloads.DonePayload(entityId, animId, ticksPlayed, interrupted, details));
    }
}
