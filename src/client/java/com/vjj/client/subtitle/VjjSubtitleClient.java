package com.vjj.client.subtitle;

import com.vjj.subtitle.ModSubtitleEntities;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 世界字幕客户端模块入口：注册实体渲染器 + UI 字幕 HUD。 */
public final class VjjSubtitleClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");
    private static boolean registered;

    private VjjSubtitleClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        EntityRendererRegistry.register(ModSubtitleEntities.SUBTITLE, VjjSubtitleRenderer::new);
        VjjSubtitleHud.register();
        LOGGER.info("[VjjNew] subtitle client initialized renderer=registered hud=registered");
    }
}
