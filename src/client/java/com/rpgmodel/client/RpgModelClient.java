package com.rpgmodel.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rpgmodel.RpgModel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * rpgmodel 客户端入口：客户端命令 {@code /rpgmodel overlay on|off}、骨骼调试 HUD、覆盖层切换键位。
 *
 * <p>split source sets 下本类只在客户端加载，专用服务器不会碰到任何客户端类。</p>
 */
public class RpgModelClient implements ClientModInitializer {
    /** 自定义键位分类（26.3 需要显式注册 Category）。 */
    public static KeyMapping.Category CATEGORY;
    /** 骨骼覆盖层开关（默认 O）。 */
    public static KeyMapping OVERLAY_KEY;

    @Override
    public void onInitializeClient() {
        CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RpgModel.MOD_ID, "main"));
        // 26.3 用 SDL，键码常量在 InputConstants（GLFW 已在 26.3 移除）。
        OVERLAY_KEY = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.rpgmodel.overlay", InputConstants.KEY_O, CATEGORY));

        RpgModelClientCommands.register();
        BoneOverlayHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(RpgModelClient::tick);

        RpgModel.LOGGER.info("[RpgModel] client initialized overlay_key={}", OVERLAY_KEY.getName());
    }

    private static void tick(Minecraft client) {
        while (OVERLAY_KEY.consumeClick()) {
            OverlayState.toggle();
        }
        if (client.player == null) {
            PoseCapture.reset();
        }
    }
}
