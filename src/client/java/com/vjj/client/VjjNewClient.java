package com.vjj.client;

import com.vjj.client.camera.VjjCameraClient;
import com.vjj.client.camera.VjjInputLock;
import com.vjj.client.camera.VjjZoomClient;
import com.vjj.client.dummy.VjjDummyClient;
import com.vjj.compat.EmfBridge;
import com.vjj.compat.PunchyBridge;
import com.vjj.compat.VjjCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * VJJ-NEW 客户端入口（{@code fabric.mod.json} 的 client entrypoint）。
 *
 * <p>职责：注册 S2C 接收器、变焦/面板键位、假人实体渲染器、EMF/Touch'n Motion 兼容桥，
 * 挂客户端 tick；断线时清理本地状态。split source sets 下本包只在客户端加载。</p>
 *
 * <p>1.1.0 玩家动画优化：{@link VjjCompat} 检测外部动画生态（EMF/ETF/T&M/EMF Compat Core/
 * Fresh Animations: Player Extension）；{@link EmfBridge} 在 EMF 存在时把 VJJ 动作接到 EMF 动画管线
 * （注册延迟到首个 tick，保证排在 Touch'n Motion 之后 = VJJ 动作最后叠加、优先可见）。</p>
 */
public final class VjjNewClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        VjjClientNet.register();
        VjjZoomClient.registerKeybind();
        VjjPanelKey.register();
        VjjDummyClient.register();
        com.vjj.client.subtitle.VjjSubtitleClient.register();
        com.vjj.client.fx.FxClient.register();
        VjjCompat.init();
        registerClientCommands();
        ClientTickEvents.END_CLIENT_TICK.register(VjjNewClient::onEndClientTick);
        VjjUiClient.LOGGER.info("[VjjNew] client initialized zoom_key={} panel_key={} dummy_renderer=registered compat=\"{}\"",
                VjjZoomClient.keyName(), VjjPanelKey.keyName(), VjjCompat.scanLine());
        VjjAutotest.init();
    }

    private static void onEndClientTick(Minecraft minecraft) {
        VjjCameraClient.tick(minecraft);
        VjjZoomClient.tick(minecraft);
        VjjInputLock.tick(minecraft);
        VjjPanelKey.tick(minecraft);
        VjjDummyClient.tick(minecraft);
        VjjCompat.tick(minecraft);
        EmfBridge.tick(minecraft);
        PunchyBridge.tick(minecraft);
        com.vjj.client.fx.FxManager.tick(minecraft);
        com.vjj.client.fx.CosmicSwordClient.tick(minecraft);
        VjjAutotest.tick(minecraft);
    }

    /** 客户端命令 {@code /vjjc compat}：打印本机动画生态检测与当前动作管线（E2E 取证也用它）。 */
    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("vjjc")
                        .then(ClientCommands.literal("compat")
                                .executes(context -> {
                                    String scan = VjjCompat.scanLine();
                                    String bridge = EmfBridge.statusLine();
                                    String punchy = PunchyBridge.statusLine();
                                    context.getSource().sendFeedback(Component.literal("[VJJ-NEW] " + scan));
                                    context.getSource().sendFeedback(Component.literal("[VJJ-NEW] 动画管线：" + bridge));
                                    context.getSource().sendFeedback(Component.literal("[VJJ-NEW] 第一人称：" + punchy));
                                    VjjUiClient.LOGGER.info("[VjjNew] client-compat {}", scan);
                                    VjjUiClient.LOGGER.info("[VjjNew] client-bridge {}", bridge);
                                    VjjUiClient.LOGGER.info("[VjjNew] client-firstperson {}", punchy);
                                    return 1;
                                }))
                        .then(ClientCommands.literal("firstperson")
                                .executes(context -> {
                                    String status = PunchyBridge.statusLine();
                                    context.getSource().sendFeedback(Component.literal("[VJJ-NEW] " + status));
                                    VjjUiClient.LOGGER.info("[VjjNew] client-firstperson {}", status);
                                    return 1;
                                }))
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal(
                                    "[VJJ-NEW] /vjjc compat — 动画生态与管线；/vjjc firstperson — 第一人称动画（Punchy）状态"));
                            return 1;
                        })));
        VjjUiClient.LOGGER.info("[VjjNew] client command registered /vjjc compat|firstperson");
    }
}
