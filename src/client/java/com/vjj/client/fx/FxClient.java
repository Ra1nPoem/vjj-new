package com.vjj.client.fx;

import com.vjj.client.ui.FxLabScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 特效库客户端入口：注册 HUD 层 + 客户端命令。
 *
 * <pre>
 * /vjjc fxlab    打开奥术特效工坊（Arcane UI，本地预览）
 * /vjjc fxstop   清空所有本地特效/屏幕覆盖
 * </pre>
 *
 * <p>服务端广播特效用 {@code /vjj fx &lt;type&gt; ...}；单机/本地预览用 FX Lab 或 {@code /vjjc fxlab}。</p>
 */
public final class FxClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");
    private static boolean registered;

    private FxClient() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                com.vjj.fx.ModFxEntities.ARCANE_CIRCLE, ArcaneCircleRenderer::new);
        ArcaneFxOverlay.register();
        com.vjj.client.bloom.BloomHud.register();
        ArcaneToastHud.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("vjjc")
                        .then(ClientCommands.literal("fxlab").executes(context -> {
                            Minecraft minecraft = Minecraft.getInstance();
                            minecraft.gui.setScreen(new FxLabScreen());
                            LOGGER.info("[VjjNew] fxlab-open");
                            return 1;
                        }))
                        .then(ClientCommands.literal("fxstop").executes(context -> {
                            FxManager.clear();
                            context.getSource().sendFeedback(Component.literal("[VJJ-NEW] 已清空本地特效"));
                            return 1;
                        }))));
        LOGGER.info("[VjjNew] fx client initialized hud=overlay+toast commands=/vjjc fxlab|fxstop");
    }
}
