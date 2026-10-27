package com.rpganim.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 网络包类型注册（26.3：{@code clientboundPlay()} / {@code serverboundPlay()}）。
 * <p><b>S2C 类型必须双端都注册</b>，否则包会被静默丢弃；因此本方法由 common 入口
 * {@code com.rpganim.RpgAnim#onInitialize} 调用一次（client 侧 common init 也会执行）。
 */
public final class NetRegistration {
    private static boolean registered;

    private NetRegistration() {
    }

    public static void registerPayloadTypes() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.SyncPayload.TYPE, ModPayloads.SyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.PlayPayload.TYPE, ModPayloads.PlayPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.StopPayload.TYPE, ModPayloads.StopPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloads.CutscenePayload.TYPE, ModPayloads.CutscenePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.EmoteRequestPayload.TYPE, ModPayloads.EmoteRequestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloads.DonePayload.TYPE, ModPayloads.DonePayload.CODEC);
    }
}
