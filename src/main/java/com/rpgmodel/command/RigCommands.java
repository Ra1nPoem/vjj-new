package com.rpgmodel.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rpgmodel.RpgModel;
import com.rpgmodel.api.AttachPoint;
import com.rpgmodel.api.Rig;
import com.rpgmodel.api.RigBone;
import com.rpgmodel.rigs.RigRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 服务端 {@code /rpgmodel} 命令树（只读查询，所有玩家可用）。
 *
 * <pre>
 * /rpgmodel list              列出全部 rig
 * /rpgmodel info &lt;rig&gt;        查看 rig 定义
 * /rpgmodel bones &lt;rig&gt;       列出骨骼（名字 / 父骨骼 / pivot / 静止旋转 / 子骨骼）
 * /rpgmodel status            加载状态与统计
 * </pre>
 *
 * <p>客户端 HUD 命令 {@code /rpgmodel overlay on|off} 属于客户端命令，不在这里注册。</p>
 */
public final class RigCommands {
    private RigCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("rpgmodel")
                        .executes(RigCommands::help)
                        .then(Commands.literal("help")
                                .executes(RigCommands::help))
                        .then(Commands.literal("list")
                                .executes(RigCommands::list))
                        .then(Commands.literal("info")
                                .then(Commands.argument("rig", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(RigRegistry.get().rigIds(), builder))
                                        .executes(RigCommands::info)))
                        .then(Commands.literal("bones")
                                .then(Commands.argument("rig", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(RigRegistry.get().rigIds(), builder))
                                        .executes(RigCommands::bones)))
                        .then(Commands.literal("status")
                                .executes(RigCommands::status))));
        RpgModel.LOGGER.info("[RpgModel] /rpgmodel commands registered");
    }

    // ─────────────────────────────────────────────────────────────────────

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        for (int index = 1; index <= 5; index++) {
            String key = "msg.rpgmodel.help." + index;
            source.sendSuccess(() -> Component.translatable(key), false);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        RigRegistry registry = RigRegistry.get();
        source.sendSuccess(() -> Component.translatable("msg.rpgmodel.list_header", registry.rigCount()), false);
        for (Rig rig : registry.rigIds().stream().map(registry::getRig).toList()) {
            if (rig == null) {
                continue;
            }
            source.sendSuccess(() -> Component.translatable("msg.rpgmodel.list_entry",
                    Component.translatable(rig.nameKey()), rig.id(), rig.boneNames().size()), false);
        }
        return registry.rigCount();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Rig rig = resolve(source, context);
        if (rig == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpgmodel.info_header",
                rig.id(), Component.translatable(rig.nameKey())), false);
        List<String> attachIds = rig.attachPoints().stream().map(AttachPoint::id).toList();
        source.sendSuccess(() -> Component.translatable("msg.rpgmodel.info_line",
                rig.rootBone(), rig.boneNames().size(),
                attachIds.isEmpty() ? Component.translatable("msg.rpgmodel.attach_none") : String.join(", ", attachIds)), false);
        for (String name : rig.boneNames()) {
            RigBone bone = rig.bones().get(name);
            if (bone == null) {
                continue;
            }
            source.sendSuccess(() -> Component.literal(" - " + formatBone(bone)), false);
        }
        return 1;
    }

    private static int bones(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Rig rig = resolve(source, context);
        if (rig == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("msg.rpgmodel.bones_header",
                rig.id(), Component.translatable(rig.nameKey())), false);
        for (String name : rig.boneNames()) {
            RigBone bone = rig.bones().get(name);
            if (bone == null) {
                continue;
            }
            source.sendSuccess(() -> Component.literal(" - " + formatBone(bone)), false);
        }
        return rig.boneNames().size();
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        RigRegistry registry = RigRegistry.get();
        source.sendSuccess(() -> Component.translatable("msg.rpgmodel.status_header",
                registry.isLoaded(), registry.rigCount(), registry.totalBoneCount()), false);
        if (!registry.isLoaded()) {
            source.sendFailure(Component.translatable("msg.rpgmodel.not_loaded"));
            return 0;
        }
        for (String id : registry.rigIds()) {
            Rig rig = registry.getRig(id);
            if (rig == null) {
                continue;
            }
            source.sendSuccess(() -> Component.translatable("msg.rpgmodel.status_entry",
                    rig.id(), rig.boneNames().size(), rig.rootBone()), false);
        }
        return 1;
    }

    private static Rig resolve(CommandSourceStack source, CommandContext<CommandSourceStack> context) {
        String rigId = StringArgumentType.getString(context, "rig");
        Rig rig = RigRegistry.get().getRig(rigId);
        if (rig == null) {
            source.sendFailure(Component.translatable("msg.rpgmodel.unknown_rig", rigId));
        }
        return rig;
    }

    private static String formatBone(RigBone bone) {
        return bone.name()
                + " parent=" + (bone.parent() == null ? "-" : bone.parent())
                + String.format(java.util.Locale.ROOT, " pivot=(%.1f,%.1f,%.1f)",
                        bone.pivotX(), bone.pivotY(), bone.pivotZ())
                + String.format(java.util.Locale.ROOT, " rot=(%.1f,%.1f,%.1f)",
                        bone.rotX(), bone.rotY(), bone.rotZ())
                + " children=" + (bone.children().isEmpty() ? "-" : String.join(",", bone.children()));
    }
}
