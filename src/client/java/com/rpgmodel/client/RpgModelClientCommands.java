package com.rpgmodel.client;

import com.rpgmodel.RpgModel;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 客户端 {@code /rpgmodel overlay on|off} 命令（只影响本地 HUD，不发包给服务端）。
 *
 * <p>服务端的 {@code /rpgmodel list|info|bones|status} 在 {@code com.rpgmodel.command.RigCommands}；
 * 两者同名根命令由 fabric 客户端命令机制按“先本地后服务端”合并。</p>
 */
public final class RpgModelClientCommands {
    private RpgModelClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("rpgmodel")
                        .executes(context -> usage(context.getSource()))
                        .then(ClientCommands.literal("overlay")
                                .executes(context -> status(context.getSource()))
                                .then(ClientCommands.literal("on")
                                        .executes(context -> set(context.getSource(), true)))
                                .then(ClientCommands.literal("off")
                                        .executes(context -> set(context.getSource(), false)))
                                .then(ClientCommands.literal("toggle")
                                        .executes(context -> {
                                            OverlayState.toggle();
                                            context.getSource().sendFeedback(Component.translatable(
                                                    OverlayState.enabled()
                                                            ? "msg.rpgmodel.overlay.on"
                                                            : "msg.rpgmodel.overlay.off",
                                                    OverlayState.currentPlayerName()));
                                            return 1;
                                        })))));
        RpgModel.LOGGER.info("[RpgModel] client commands registered /rpgmodel overlay on|off");
    }

    private static int set(FabricClientCommandSource source, boolean value) {
        LocalPlayer player = source.getPlayer();
        if (player == null || player.level() == null) {
            source.sendError(Component.translatable("msg.rpgmodel.overlay.no_player"));
            return 0;
        }
        OverlayState.set(value);
        source.sendFeedback(Component.translatable(
                value ? "msg.rpgmodel.overlay.on" : "msg.rpgmodel.overlay.off",
                player.getGameProfile().name()));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("msg.rpgmodel.overlay.status",
                OverlayState.enabled() ? "on" : "off"));
        return 1;
    }

    private static int usage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("msg.rpgmodel.overlay.usage"));
        return 1;
    }
}
