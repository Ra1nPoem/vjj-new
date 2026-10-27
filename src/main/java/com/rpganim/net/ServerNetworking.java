package com.rpganim.net;

import com.rpganim.RpgAnim;
import com.rpganim.server.AnimServerManager;
import com.rpganim.server.ContentStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;

/**
 * 服务端网络层：S2C 发包封装 + C2S 全局接收器。
 *
 * <ul>
 *   <li>S2C：sync / play / stop / cutscene；发送前一律 {@link ServerPlayNetworking#canSend} 守卫，
 *       对端没装 rpganim 时打 {@code *-skip ... reason=no-client-mod} 而不是抛异常。</li>
 *   <li>C2S：EmoteRequest → {@link AnimServerManager#handleEmoteRequest}（校验类别 + 20t 节流 + 广播）；
 *       Done → 只记日志（客户端回执，用于取证，不参与演算）。</li>
 * </ul>
 *
 * <p>payload 类型注册在 {@link NetRegistration#registerPayloadTypes()}（由 common 入口调用一次）。
 * 日志契约见 docs/contract.md。</p>
 */
public final class ServerNetworking {
    private ServerNetworking() {
    }

    /** 注册 C2S 全局接收器（common 入口在 payload 类型注册之后调用一次）。 */
    public static void registerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.EmoteRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> AnimServerManager.get().handleEmoteRequest(player, payload.animId()));
        });
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.DonePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> RpgAnim.LOGGER.info(
                    "[RpgAnim] client-done player={} entity={} anim={} ticks={} interrupted={} details={}",
                    nameOf(player), payload.entityId(), payload.animId(), payload.ticksPlayed(),
                    payload.interrupted(), payload.details()));
        });
        RpgAnim.LOGGER.info("[RpgAnim] network receivers registered");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 发送守卫
    // ─────────────────────────────────────────────────────────────────────

    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player != null && !player.hasDisconnected() && ServerPlayNetworking.canSend(player, type);
    }

    public static boolean canSendPlay(ServerPlayer player) {
        return canSend(player, ModPayloads.PlayPayload.TYPE);
    }

    public static boolean canSendCutscene(ServerPlayer player) {
        return canSend(player, ModPayloads.CutscenePayload.TYPE);
    }

    /** 通用发包（带 canSend 守卫）；返回是否真的发出。 */
    public static boolean send(ServerPlayer player, CustomPacketPayload payload) {
        if (payload == null || !canSend(player, payload.type())) {
            return false;
        }
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 具体发送
    // ─────────────────────────────────────────────────────────────────────

    /** 玩家 JOIN 时同步整包内容。 */
    public static boolean sendSync(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        ContentStore store = ContentStore.get();
        String json = store.rawJson();
        if (json == null || json.isBlank()) {
            RpgAnim.LOGGER.info("[RpgAnim] sync-skip player={} reason=no-content", nameOf(player));
            return false;
        }
        if (!canSend(player, ModPayloads.SyncPayload.TYPE)) {
            RpgAnim.LOGGER.info("[RpgAnim] sync-skip player={} reason=no-client-mod", nameOf(player));
            return false;
        }
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        ServerPlayNetworking.send(player, new ModPayloads.SyncPayload(
                json, store.animationCount(), store.cutsceneCount()));
        RpgAnim.LOGGER.info("[RpgAnim] sync-sent player={} animations={} cutscenes={} bytes={}",
                nameOf(player), store.animationCount(), store.cutsceneCount(), bytes);
        return true;
    }

    public static boolean sendPlay(ServerPlayer player, ModPayloads.PlayPayload payload) {
        return send(player, payload);
    }

    public static boolean sendStop(ServerPlayer player, ModPayloads.StopPayload payload) {
        return send(player, payload);
    }

    /** 发过场开始/结束（客户端 HUD + autotest 用）。 */
    public static boolean sendCutscene(ServerPlayer player, ModPayloads.CutscenePayload payload) {
        return send(player, payload);
    }

    private static String nameOf(ServerPlayer player) {
        return player == null ? "null" : player.getGameProfile().name();
    }
}
