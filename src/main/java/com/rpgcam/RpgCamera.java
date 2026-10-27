package com.rpgcam;

import com.rpgcam.cue.CueRegistry;
import com.rpgcam.net.ModNetworking;
import com.rpgcam.server.CameraCommands;
import com.rpgcam.server.CameraCueManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RpgCamera 公共入口（专用服务器与客户端共用的那一半）。
 * 客户端入口在 {@code com.rpgcam.client.RpgCameraClient}。
 */
public class RpgCamera implements ModInitializer {
    public static final String MOD_ID = "rpgcam";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[RpgCamera] initializing (Minecraft 26.3 / Fabric) version={}", VERSION);

        CueRegistry.init();
        ModNetworking.registerCommon();
        CameraCueManager.get().registerEvents();
        CameraCommands.register();

        boolean questguide = FabricLoader.getInstance().isModLoaded("questguide");
        LOGGER.info("[RpgCamera] initialized styles={} interop.questguide={}", CueRegistry.size(), questguide);
        if (questguide) {
            LOGGER.info("[RpgCamera] interop linked with questguide: quest events -> camera cues (source=questguide)");
        }
    }
}
