package com.rpganim.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RpgAnim 客户端入口（{@code fabric.mod.json} 的 client entrypoint）：
 * 注册网络接收器（S2C sync/play/stop/cutscene）、键位（H 下一个 emote）、HUD、autotest，
 * 并在每个客户端 tick 推进本地动作时钟。
 * <p>split source sets 下本包只在客户端加载，专用服务器不会碰到任何客户端类。
 */
public class RpgAnimClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("rpganim-client");

    /** 自定义键位分类（26.3 需要显式注册 Category）。 */
    public static KeyMapping.Category CATEGORY;
    /** H：循环选下一个 emote 类动作并 C2S 请求（服务端校验/广播）。 */
    public static KeyMapping NEXT_EMOTE;

    /** 键位 H 的轮转指针；-1 让第一次按 H 选到列表第一个。 */
    private static int emoteCursor = -1;

    @Override
    public void onInitializeClient() {
        CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rpganim", "main"));
        NEXT_EMOTE = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.rpganim.next_emote", InputConstants.KEY_H, CATEGORY));

        ClientNetworking.registerReceivers();
        AnimHud.register();
        AnimAutotest.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientAnimState.tick();
            AnimAutotest.tick(client);
            while (NEXT_EMOTE.consumeClick()) {
                requestNextEmote();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientAnimState.reset("disconnect"));

        LOGGER.info("[RpgAnim] client initialized keys=1 next_emote={} category={}",
                NEXT_EMOTE.getName(), CATEGORY.label().getString());
    }

    /** 键位 H：循环选下一个 emote 类动作，发 C2S 请求（服务端校验分类+节流后广播回来）。 */
    private static void requestNextEmote() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        List<String> emoteIds = ClientAnimState.emoteIds();
        if (emoteIds.isEmpty()) {
            LOGGER.warn("[RpgAnim] emote-request skipped reason=no-content");
            return;
        }
        emoteCursor = Math.floorMod(emoteCursor + 1, emoteIds.size());
        ClientNetworking.sendEmoteRequest(emoteIds.get(emoteCursor));
    }
}
