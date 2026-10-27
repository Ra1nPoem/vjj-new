package com.rpganim.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rpganim.RpgAnim;
import com.rpganim.anim.CutsceneDefinition;
import com.rpganim.cutscene.CutsceneManager;
import com.rpganim.interop.RpgAnimInterop;
import com.rpganim.server.ContentStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /cutscene} 命令树（镜头 + 动作时间轴过场）。
 *
 * <pre>
 * /cutscene list                    列出全部过场（所有人）
 * /cutscene info &lt;id&gt;              查看过场详情（所有人）
 * /cutscene play &lt;id&gt; [target]      播放过场（GM；默认目标为自己）
 * /cutscene stop [target]           停止过场（GM）
 * /cutscene status [target]         当前过场状态（所有人）
 * /cutscene demo [target]           依次演示全部过场（GM）
 * </pre>
 *
 * <p>没有 rpgcam 时自动降级为「无镜头动作过场」，命令仍成功并提示 camera_failed。
 * 日志为 {@code cutscene-start / cutscene-step / cutscene-camera / cutscene-end}。</p>
 */
public final class CutsceneCommands {
    private CutsceneCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("cutscene")
                        .executes(CutsceneCommands::help)
                        .then(Commands.literal("help")
                                .executes(CutsceneCommands::help))
                        .then(Commands.literal("list")
                                .executes(CutsceneCommands::list))
                        .then(Commands.literal("info")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(ContentStore.get().cutsceneIds(), builder))
                                        .executes(CutsceneCommands::info)))
                        .then(Commands.literal("play")
                                .requires(CommandSupport.ADMIN)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(ContentStore.get().cutsceneIds(), builder))
                                        .executes(context -> play(context, null))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(context -> play(context,
                                                        EntityArgument.getPlayer(context, "target"))))))
                        .then(Commands.literal("stop")
                                .requires(CommandSupport.ADMIN)
                                .executes(context -> stop(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> stop(context,
                                                EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("status")
                                .executes(context -> status(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> status(context,
                                                EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("demo")
                                .requires(CommandSupport.ADMIN)
                                .executes(context -> demo(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> demo(context,
                                                EntityArgument.getPlayer(context, "target")))))));
        RpgAnim.LOGGER.info("[RpgAnim] /cutscene commands registered");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 子命令
    // ─────────────────────────────────────────────────────────────────────

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.help.1"), false);
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.help.2"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.list.header",
                ContentStore.get().cutsceneCount()), false);
        for (CutsceneDefinition definition : ContentStore.get().cutscenes()) {
            source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.list.line",
                    Component.translatable(definition.titleKey()), definition.id(), definition.durationTicks(),
                    cameraStyle(definition)), false);
        }
        return ContentStore.get().cutsceneCount();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        CutsceneDefinition definition = ContentStore.get().cutscene(id);
        if (definition == null) {
            source.sendFailure(Component.translatable("cutscene.rpganim.msg.unknown", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(definition.titleKey())
                .append(Component.literal(" [" + definition.id() + "]")), false);
        source.sendSuccess(() -> Component.literal("duration=" + definition.durationTicks()
                + "t camera=" + cameraStyle(definition) + " steps=" + definition.steps().size()), false);
        for (var step : definition.steps()) {
            source.sendSuccess(() -> Component.literal(" - at=" + step.at() + " anim=" + step.animId()
                    + (step.loop() ? " loop" : "")), false);
        }
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        CutsceneDefinition definition = ContentStore.get().cutscene(id);
        if (definition == null) {
            source.sendFailure(Component.translatable("cutscene.rpganim.msg.unknown", id));
            return 0;
        }
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        CutsceneManager.get().cancelDemo(target);
        int played = CutsceneManager.get().play(target, id);
        if (played <= 0) {
            source.sendFailure(Component.translatable("cutscene.rpganim.msg.unknown", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.played",
                Component.translatable(definition.titleKey()), CommandSupport.nameOf(target)), true);
        if (definition.hasCamera() && !RpgAnimInterop.isCameraAvailable()) {
            source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.camera_failed"), false);
        }
        return played;
    }

    private static int stop(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        boolean stopped = CutsceneManager.get().stop(target, "stop");
        if (stopped) {
            source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.stopped",
                    CommandSupport.nameOf(target)), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.idle",
                CommandSupport.nameOf(target)), false);
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        String cutsceneId = CutsceneManager.get().activeCutsceneId(target);
        if (cutsceneId == null) {
            source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.idle",
                    CommandSupport.nameOf(target)), false);
            return 0;
        }
        CutsceneDefinition definition = ContentStore.get().cutscene(cutsceneId);
        long remaining = CutsceneManager.get().remainingTicks(target);
        Component display = definition == null
                ? Component.literal(cutsceneId) : Component.translatable(definition.titleKey());
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.status",
                CommandSupport.nameOf(target), display, Long.toString(remaining)), false);
        return 1;
    }

    private static int demo(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        int total = CutsceneManager.get().startDemo(target);
        if (total <= 0) {
            source.sendFailure(Component.literal("No cutscenes available (content not loaded)"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("cutscene.rpganim.msg.demo.start", total), true);
        return total;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static String cameraStyle(CutsceneDefinition definition) {
        return definition.hasCamera() ? definition.camera().style() : "none";
    }
}
