package com.vjj.fx;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vjj.VjjNew;
import com.vjj.net.VjjNet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;
import java.util.Random;
import java.util.function.Predicate;

/**
 * 特效命令：{@code /vjj fx ...}（OP）。
 *
 * <pre>
 * /vjj fx &lt;type&gt; [color] [scale]                       # 在执行者位置触发
 * /vjj fx at &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;type&gt; [color] [scale]        # 在世界坐标触发
 * /vjj fx player &lt;targets&gt; &lt;type&gt; [color] [scale]     # 在每个目标位置触发
 * </pre>
 *
 * <p>效果由客户端 {@code com.vjj.client.fx.FxManager} / {@code ArcaneFxOverlay} 渲染；
 * 服务端只广播 {@link FxPayload}，不依赖客户端资源包。</p>
 */
public final class FxCommands {
    private static final Predicate<CommandSourceStack> ADMIN =
            source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());

    private FxCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("fx")
                .requires(ADMIN)
                .then(fxEffectBranch())
                .then(Commands.literal("at")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(-30000000.0, 30000000.0))
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-2048.0, 2048.0))
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30000000.0, 30000000.0))
                                                .then(fxEffectBranch())))))
                .then(Commands.literal("player")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(fxEffectBranch())));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> fxEffectBranch() {
        return Commands.argument("type", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        java.util.Arrays.stream(FxType.values()).map(type -> type.id).toList(), builder))
                .executes(context -> trigger(context, null, null, null))
                .then(Commands.argument("color", StringArgumentType.word())
                        .executes(context -> trigger(context, null, null, null))
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.2, 6.0))
                                .executes(context -> trigger(context, null, null, null))));
    }

    /** 从参数里解析 type/color/scale（分支可能没有 color/scale 节点）。 */
    private static int trigger(CommandContext<CommandSourceStack> context, String unusedColor,
                               String unusedScale, String unusedDuration) throws CommandSyntaxException {
        FxType type = FxType.byId(StringArgumentType.getString(context, "type"));
        if (type == null) {
            context.getSource().sendFailure(Component.literal("未知特效类型（可用：" + availableTypes() + "）"));
            return 0;
        }
        String colorRaw = hasArgument(context, "color") ? StringArgumentType.getString(context, "color") : null;
        int color = colorRaw == null ? type.defaultColor : parseColor(colorRaw);
        double scale = hasArgument(context, "scale") ? DoubleArgumentType.getDouble(context, "scale") : 1.0;
        Vec3 anchor = resolveAnchor(context);
        if (type == FxType.CIRCLE) {
            return spawnCircle(context, anchor, color, (float) scale);
        }
        int sent = broadcast(context.getSource(), type, anchor, color, (float) scale);
        int count = sent;
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "特效 %s 已发送给 %d 名玩家 @ %.2f,%.2f,%.2f 颜色=#%08X 缩放=%.2f",
                type.id, count, anchor.x, anchor.y, anchor.z, color, scale)), false);
        VjjNew.LOGGER.info("[VjjNew] fx-trigger type={} pos={},{},{} color={} scale={} players={}",
                type.id, anchor.x, anchor.y, anchor.z, Integer.toHexString(color), scale, count);
        return count;
    }

    private static Vec3 resolveAnchor(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (hasArgument(context, "targets")) {
            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
            if (!targets.isEmpty()) {
                ServerPlayer first = targets.iterator().next();
                return first.position();
            }
        }
        if (hasArgument(context, "x")) {
            return new Vec3(DoubleArgumentType.getDouble(context, "x"),
                    DoubleArgumentType.getDouble(context, "y"),
                    DoubleArgumentType.getDouble(context, "z"));
        }
        return context.getSource().getPlayerOrException().position();
    }

    /** 大型法阵走实体渲染（服务端生成、客户端画多层光效），其余特效走 payload 粒子。 */
    private static int spawnCircle(CommandContext<CommandSourceStack> context, Vec3 pos, int color, float scale) {
        net.minecraft.server.level.ServerLevel level = context.getSource().getLevel();
        ArcaneCircleEntity circle = new ArcaneCircleEntity(ModFxEntities.ARCANE_CIRCLE, level);
        circle.setPos(pos.x, pos.y + 0.03, pos.z);
        circle.configure(color, scale, new Random().nextLong(), FxType.CIRCLE.defaultDurationTicks);
        level.addFreshEntity(circle);
        long burstSeed = new Random().nextLong();
        VjjNet.broadcastAll(level.getServer(), new FxPayload(FxType.BURST.id, pos.x, pos.y, pos.z, color, scale, FxType.BURST.defaultDurationTicks, burstSeed));
        int id = circle.getId();
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "奥术法阵已生成 #%d @ %.2f,%.2f,%.2f 颜色=#%08X 缩放=%.2f 时长=%dt",
                id, pos.x, pos.y, pos.z, color, scale, FxType.CIRCLE.defaultDurationTicks)), false);
        VjjNew.LOGGER.info("[VjjNew] fx-circle id={} pos={},{},{} color={} scale={}",
                id, pos.x, pos.y, pos.z, Integer.toHexString(color), scale);
        return 1;
    }
    private static int broadcast(CommandSourceStack source, FxType type, Vec3 pos, int color, float scale) {
        FxPayload payload = new FxPayload(type.id, pos.x, pos.y, pos.z, color, scale,
                type.defaultDurationTicks, new Random().nextLong());
        return VjjNet.broadcastAll(source.getServer(), payload);
    }

    private static boolean hasArgument(CommandContext<CommandSourceStack> context, String name) {
        try {
            context.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String availableTypes() {
        return String.join(", ", java.util.Arrays.stream(FxType.values()).map(type -> type.id).toList());
    }

    /** 颜色名 / 16 进制（不带 #） → ARGB。 */
    private static int parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0xFFFFFFFF;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int named = switch (value) {
            case "white" -> 0xFFFFFFFF;
            case "gold", "orange" -> 0xFFFFAA00;
            case "red" -> 0xFFFF5555;
            case "green" -> 0xFF55FF55;
            case "aqua", "cyan" -> 0xFF55FFFF;
            case "blue" -> 0xFF5555FF;
            case "purple", "violet" -> 0xFFFF55FF;
            case "black" -> 0xFF000000;
            default -> 0;
        };
        if (named != 0) {
            return named;
        }
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            return (int) Long.parseLong(hex, 16) | 0xFF000000;
        } catch (NumberFormatException ignored) {
            return 0xFFFFFFFF;
        }
    }
}
