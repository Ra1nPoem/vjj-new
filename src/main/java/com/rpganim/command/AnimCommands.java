package com.rpganim.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rpganim.RpgAnim;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.net.ModPayloads;
import com.rpganim.server.AnimServerManager;
import com.rpganim.server.ContentStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /anim} 命令树（服务端权威动作控制）。
 *
 * <pre>
 * /anim list                         列出全部动作（所有人）
 * /anim info &lt;id&gt;                   查看动作详情（所有人）
 * /anim play &lt;id&gt; [target] [duration]  播放（GM；默认目标为自己）
 * /anim stop [target]                停止（GM）
 * /anim emote &lt;id&gt;                  玩家自助播放 emote/combat 类动作（所有人，自用）
 * /anim demo [target]                依次演示全部动作（GM，间隔 30t）
 * /anim status [target]              当前动作状态（所有人）
 * </pre>
 *
 * <p>时长单位 tick（20t = 1s），范围 20–6000；不填用动作默认时长。
 * 日志为 {@code anim-start / anim-end / anim-denied}（见 docs/contract.md）。</p>
 */
public final class AnimCommands {
    private AnimCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("anim")
                        .executes(AnimCommands::help)
                        .then(Commands.literal("help")
                                .executes(AnimCommands::help))
                        .then(Commands.literal("list")
                                .executes(AnimCommands::list))
                        .then(Commands.literal("info")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(ContentStore.get().animationIds(), builder))
                                        .executes(AnimCommands::info)))
                        .then(Commands.literal("play")
                                .requires(CommandSupport.ADMIN)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(ContentStore.get().animationIds(), builder))
                                        .executes(context -> play(context, null, -1))
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(context -> play(context,
                                                        EntityArgument.getPlayer(context, "target"), -1))
                                                .then(Commands.argument("duration",
                                                                IntegerArgumentType.integer(20, ModPayloads.MAX_DURATION))
                                                        .executes(context -> play(context,
                                                                EntityArgument.getPlayer(context, "target"),
                                                                IntegerArgumentType.getInteger(context, "duration")))))))
                        .then(Commands.literal("stop")
                                .requires(CommandSupport.ADMIN)
                                .executes(context -> stop(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> stop(context,
                                                EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("emote")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(selfServiceIds(), builder))
                                        .executes(AnimCommands::emote)))
                        .then(Commands.literal("demo")
                                .requires(CommandSupport.ADMIN)
                                .executes(context -> demo(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> demo(context,
                                                EntityArgument.getPlayer(context, "target")))))
                        .then(Commands.literal("status")
                                .executes(context -> status(context, null))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> status(context,
                                                EntityArgument.getPlayer(context, "target")))))));
        RpgAnim.LOGGER.info("[RpgAnim] /anim commands registered");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 子命令
    // ─────────────────────────────────────────────────────────────────────

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("msg.rpganim.help.1"), false);
        source.sendSuccess(() -> Component.translatable("msg.rpganim.help.2"), false);
        source.sendSuccess(() -> Component.translatable("msg.rpganim.help.3"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<AnimDefinition> animations = ContentStore.get().animations();
        source.sendSuccess(() -> Component.translatable("msg.rpganim.list.header", animations.size()), false);
        for (AnimDefinition definition : animations) {
            source.sendSuccess(() -> Component.translatable("msg.rpganim.list.line",
                    Component.translatable(definition.nameKey()), definition.id(),
                    definition.durationTicks(), category(definition)), false);
        }
        return animations.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        AnimDefinition definition = ContentStore.get().animation(id);
        if (definition == null) {
            source.sendFailure(Component.translatable("msg.rpganim.unknown", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpganim.info.header", definition.id()), false);
        source.sendSuccess(() -> Component.translatable("msg.rpganim.info.detail",
                category(definition), definition.durationTicks(), definition.loop(),
                definition.priority(), definition.rig(), definition.boneCount()), false);
        source.sendSuccess(() -> Component.translatable("msg.rpganim.bones",
                String.join(", ", definition.boneNames())), false);
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget, int duration) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        AnimDefinition definition = ContentStore.get().animation(id);
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (definition == null) {
            // 有目标时仍走一遍调度器：留下设计 §5.8 要求的 anim-denied ... reason=unknown 证据行。
            if (target != null) {
                AnimServerManager.get().play(target, id, duration, "command");
            }
            source.sendFailure(Component.translatable("msg.rpganim.unknown", id));
            return 0;
        }
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        AnimServerManager.get().cancelDemo(target);
        int played = AnimServerManager.get().play(target, id, duration, "command");
        if (played <= 0) {
            source.sendFailure(Component.translatable(ContentStore.get().isDisabled(id)
                    ? "msg.rpganim.unknown" : "msg.rpganim.no_client", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpganim.played",
                id, CommandSupport.nameOf(target), played), true);
        return played;
    }

    private static int stop(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        boolean stopped = AnimServerManager.get().stop(target, "stop");
        if (stopped) {
            source.sendSuccess(() -> Component.translatable("msg.rpganim.stopped",
                    CommandSupport.nameOf(target)), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpganim.idle",
                CommandSupport.nameOf(target)), false);
        return 0;
    }

    private static int emote(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        String id = StringArgumentType.getString(context, "id");
        AnimDefinition definition = ContentStore.get().animation(id);
        if (definition == null) {
            source.sendFailure(Component.translatable("msg.rpganim.unknown", id));
            return 0;
        }
        if (!definition.isEmote() && !definition.isCombat()) {
            source.sendFailure(Component.translatable("msg.rpganim.category_denied", id));
            return 0;
        }
        AnimServerManager.get().cancelDemo(player);
        int played = AnimServerManager.get().play(player, id, -1, "command");
        if (played <= 0) {
            source.sendFailure(Component.translatable("msg.rpganim.no_client"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpganim.played",
                id, CommandSupport.nameOf(player), played), false);
        return played;
    }

    private static int demo(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        int total = AnimServerManager.get().startDemo(target);
        if (total <= 0) {
            source.sendFailure(Component.literal("No animations available (content not loaded)"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpganim.demo.start", total), true);
        return total;
    }

    private static int status(CommandContext<CommandSourceStack> context, ServerPlayer explicitTarget) {
        CommandSourceStack source = context.getSource();
        ServerPlayer target = CommandSupport.resolveTarget(source, explicitTarget);
        if (target == null) {
            source.sendFailure(Component.translatable("msg.rpganim.player_only"));
            return 0;
        }
        String animId = AnimServerManager.get().activeAnimId(target);
        if (animId == null) {
            source.sendSuccess(() -> Component.translatable("msg.rpganim.idle",
                    CommandSupport.nameOf(target)), false);
            return 0;
        }
        AnimDefinition definition = ContentStore.get().animation(animId);
        long remaining = AnimServerManager.get().remainingTicks(target);
        Component display = definition == null
                ? Component.literal(animId) : Component.translatable(definition.nameKey());
        source.sendSuccess(() -> Component.translatable("msg.rpganim.status",
                CommandSupport.nameOf(target), display,
                remaining < 0 ? "loop" : Long.toString(remaining)), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static List<String> selfServiceIds() {
        List<String> ids = new ArrayList<>();
        for (AnimDefinition definition : ContentStore.get().animations()) {
            if (definition.isEmote() || definition.isCombat()) {
                ids.add(definition.id());
            }
        }
        return ids;
    }

    private static Component category(AnimDefinition definition) {
        return Component.translatable("category.rpganim." + definition.category());
    }
}
