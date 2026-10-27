package com.rpgcam.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rpgcam.RpgCamera;
import com.rpgcam.cue.CueRegistry;
import com.rpgcam.cue.CueSpec;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * {@code /cinematic} 命令树（RPG 演出/管理员用）。
 *
 * <pre>
 * /cinematic list                     列出全部运镜样式
 * /cinematic info &lt;style&gt;             查看样式参数
 * /cinematic play &lt;style&gt; [target] [duration]   播放（默认目标为自己；控制台必须给 target）
 * /cinematic stop [target]            停止
 * /cinematic preview &lt;style&gt; [duration]         玩家自助预览（无需 op）
 * /cinematic demo [target]            依次播放全部样式（验收/演出）
 * /cinematic status [target]          当前镜头状态
 * </pre>
 */
public final class CameraCommands {
    private CameraCommands() {
    }

    private static final Predicate<CommandSourceStack> ADMIN =
            source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("cinematic")
                    .executes(CameraCommands::help)
                    .then(Commands.literal("help")
                            .executes(CameraCommands::help))
                    .then(Commands.literal("list")
                            .executes(CameraCommands::list))
                    .then(Commands.literal("info")
                            .then(Commands.argument("style", StringArgumentType.word())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(CueRegistry.ids(), builder))
                                    .executes(CameraCommands::info)))
                    .then(Commands.literal("play")
                            .requires(ADMIN)
                            .then(Commands.argument("style", StringArgumentType.word())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(CueRegistry.ids(), builder))
                                    .executes(context -> play(context, null, -1))
                                    .then(Commands.argument("target", EntityArgument.entity())
                                            .executes(context -> play(context, EntityArgument.getEntity(context, "target"), -1))
                                            .then(Commands.argument("duration", IntegerArgumentType.integer(20, 6000))
                                                    .executes(context -> play(context,
                                                            EntityArgument.getEntity(context, "target"),
                                                            IntegerArgumentType.getInteger(context, "duration")))))))
                    .then(Commands.literal("stop")
                            .executes(context -> stop(context, null))
                            .then(Commands.argument("target", EntityArgument.entity())
                                    .requires(ADMIN)
                                    .executes(context -> stop(context, EntityArgument.getEntity(context, "target")))))
                    .then(Commands.literal("preview")
                            .then(Commands.argument("style", StringArgumentType.word())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(CueRegistry.ids(), builder))
                                    .executes(context -> preview(context, -1))
                                    .then(Commands.argument("duration", IntegerArgumentType.integer(20, 6000))
                                            .executes(context -> preview(context,
                                                    IntegerArgumentType.getInteger(context, "duration"))))))
                    .then(Commands.literal("demo")
                            .requires(ADMIN)
                            .executes(context -> demo(context, null))
                            .then(Commands.argument("target", EntityArgument.entity())
                                    .executes(context -> demo(context, EntityArgument.getEntity(context, "target")))))
                    .then(Commands.literal("status")
                            .executes(context -> status(context, null))
                            .then(Commands.argument("target", EntityArgument.entity())
                                    .requires(ADMIN)
                                    .executes(context -> status(context, EntityArgument.getEntity(context, "target"))))));
        });
        RpgCamera.LOGGER.info("[RpgCamera] /cinematic commands registered");
    }

    // ─────────────────────────────────────────────────────────────────────

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.help.1"), false);
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.help.2"), false);
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.help.3"), false);
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.help.4"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.styles_header", CueRegistry.size()), false);
        for (CueSpec spec : CueRegistry.all()) {
            source.sendSuccess(() -> Component.literal(" - ")
                    .append(Component.translatable(spec.titleKey()))
                    .append(Component.literal(" [" + spec.id() + ", " + spec.durationTicks() + "t]")), false);
        }
        return CueRegistry.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String styleId = StringArgumentType.getString(context, "style");
        CueSpec spec = CueRegistry.get(styleId);
        if (spec == null) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.unknown_style", styleId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(spec.titleKey())
                .append(Component.literal(" [" + spec.id() + "]")), false);
        source.sendSuccess(() -> Component.literal("motion=" + spec.motion()
                + " anchor=" + spec.anchor() + " look=" + spec.look()
                + " duration=" + spec.durationTicks() + "t"), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "dist=%.2f->%.2f angle=%.1f sweep=%.1f height=%.2f->%.2f fov=%.1f->%.1f letterbox=%.2f",
                spec.startDist(), spec.endDist(), spec.startAngleDeg(), spec.sweepDeg(),
                spec.startHeight(), spec.endHeight(), spec.fovStart(), spec.fovEnd(), spec.letterbox())), false);
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> context, Entity rawTarget, int duration) {
        CommandSourceStack source = context.getSource();
        String styleId = StringArgumentType.getString(context, "style");
        ServerPlayer player;
        try {
            if (rawTarget != null) {
                if (rawTarget instanceof ServerPlayer targetPlayer) {
                    player = targetPlayer;
                } else {
                    source.sendFailure(Component.translatable("cinematic.rpgcam.msg.not_player"));
                    return 0;
                }
            } else {
                player = source.getPlayerOrException();
            }
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.need_target"));
            return 0;
        }
        if (!CueRegistry.has(styleId)) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.unknown_style", styleId));
            return 0;
        }
        int played = CameraCueManager.get().play(player, styleId, duration, null, null, "", "command");
        if (played <= 0) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.play_failed", styleId));
            return 0;
        }
        final ServerPlayer targetPlayer = player;
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.play",
                styleId, targetPlayer.getGameProfile().name(), played), true);
        return played;
    }

    private static int preview(CommandContext<CommandSourceStack> context, int duration) {
        CommandSourceStack source = context.getSource();
        String styleId = StringArgumentType.getString(context, "style");
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.need_player"));
            return 0;
        }
        if (!CueRegistry.has(styleId)) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.unknown_style", styleId));
            return 0;
        }
        int played = CameraCueManager.get().play(player, styleId, duration, null, null, "", "preview");
        if (played <= 0) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.play_failed", styleId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.preview", styleId, played), false);
        return played;
    }

    private static int stop(CommandContext<CommandSourceStack> context, Entity rawTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            if (rawTarget != null) {
                if (rawTarget instanceof ServerPlayer targetPlayer) {
                    player = targetPlayer;
                } else {
                    source.sendFailure(Component.translatable("cinematic.rpgcam.msg.not_player"));
                    return 0;
                }
            } else {
                player = source.getPlayerOrException();
            }
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.need_target"));
            return 0;
        }
        CameraCueManager.get().cancelDemo(player);
        boolean stopped = CameraCueManager.get().stop(player, "command");
        final String playerName = player.getGameProfile().name();
        if (stopped) {
            source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.stop", playerName), false);
        } else {
            source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.no_active", playerName), false);
        }
        return stopped ? 1 : 0;
    }

    private static int demo(CommandContext<CommandSourceStack> context, Entity rawTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            if (rawTarget != null) {
                if (rawTarget instanceof ServerPlayer targetPlayer) {
                    player = targetPlayer;
                } else {
                    source.sendFailure(Component.translatable("cinematic.rpgcam.msg.not_player"));
                    return 0;
                }
            } else {
                player = source.getPlayerOrException();
            }
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.need_target"));
            return 0;
        }
        CameraCueManager.get().startDemoAll(player);
        source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.demo",
                player.getGameProfile().name(), CueRegistry.size()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, Entity rawTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            if (rawTarget != null) {
                if (rawTarget instanceof ServerPlayer targetPlayer) {
                    player = targetPlayer;
                } else {
                    source.sendFailure(Component.translatable("cinematic.rpgcam.msg.not_player"));
                    return 0;
                }
            } else {
                player = source.getPlayerOrException();
            }
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.translatable("cinematic.rpgcam.msg.need_target"));
            return 0;
        }
        String info = CameraCueManager.get().describeActive(player);
        if (info == null) {
            source.sendSuccess(() -> Component.translatable("cinematic.rpgcam.msg.no_active",
                    player.getGameProfile().name()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(player.getGameProfile().name() + ": " + info), false);
        return 1;
    }
}
