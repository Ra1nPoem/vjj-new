package com.rpgcam.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.rpgcam.RpgCamera;
import com.rpgcam.cue.CueRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * RpgCamera 客户端入口：网络接收器、键位（K 预览下一个样式 / L 停止）、每 tick 驱动、autotest。
 */
public class RpgCameraClient implements ClientModInitializer {
    public static final String MOD_ID = "rpgcam";

    public static KeyMapping.Category CATEGORY;
    public static KeyMapping PREVIEW_NEXT;
    public static KeyMapping STOP;

    private static int previewIndex;

    @Override
    public void onInitializeClient() {
        CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        PREVIEW_NEXT = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.rpgcam.preview_next", InputConstants.KEY_K, CATEGORY));
        STOP = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.rpgcam.stop", InputConstants.KEY_L, CATEGORY));

        ClientNetworking.registerReceivers();
        CameraAutotest.register();

        ClientTickEvents.END_CLIENT_TICK.register(RpgCameraClient::onClientTick);

        RpgCamera.LOGGER.info("[RpgCamera] client initialized keys={}/{} category={}",
                PREVIEW_NEXT.getName(), STOP.getName(), CATEGORY);
    }

    private static void onClientTick(Minecraft mc) {
        // 先推进导演（含自动收尾），再处理键位
        CameraDirector director = CameraDirector.get();
        director.onClientTick(mc);

        while (PREVIEW_NEXT.consumeClick()) {
            List<String> ids = CueRegistry.ids();
            if (!ids.isEmpty()) {
                String styleId = ids.get(Math.floorMod(previewIndex, ids.size()));
                previewIndex++;
                director.playLocal(styleId, -1);
                RpgCamera.LOGGER.info("[RpgCamera] key-preview style={}", styleId);
            }
        }
        while (STOP.consumeClick()) {
            if (director.isActive()) {
                director.stopLocal();
                RpgCamera.LOGGER.info("[RpgCamera] key-stop");
            }
        }
    }
}
