package com.vjj.subtitle;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vjj.VjjNew;
import com.vjj.net.VjjNet;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * 字幕命令：{@code /subtitle ...}（独立根命令）与 {@code /vjj subtitle ...} 共用同一棵子树。
 *
 * <pre>
 * # ① UI 字幕（显示在玩家屏幕上，位置每句可不同；x/y 是屏幕百分比 0..100）
 * /subtitle ui &lt;targets&gt; &lt;x&gt; &lt;y&gt; &lt;durationTicks&gt; &lt;text...&gt;
 * /subtitle uistyle &lt;targets&gt; &lt;x&gt; &lt;y&gt; &lt;durationTicks&gt; &lt;color&gt; &lt;scale&gt; &lt;text...&gt;
 * /subtitle say &lt;targets&gt; &lt;text...&gt;                 # 默认 50%,88%,120t,白色,1.0
 * /subtitle clearui [&lt;targets&gt;]
 *
 * # ② 世界字幕（世界空间固定位置/朝向，前后两面渲染，无背景板）
 * /subtitle world &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;yaw&gt; &lt;pitch&gt; &lt;scale&gt; &lt;color&gt; &lt;text...&gt;
 * /subtitle here &lt;text...&gt;                            # 玩家眼前 3 格、正对玩家
 * /subtitle wall &lt;text...&gt;                            # 视线 8 格内命中方块 → 贴墙（正对玩家）
 * /subtitle clearworld
 * /subtitle list
 * </pre>
 *
 * <p>颜色支持：{@code white/gold/yellow/red/green/aqua/blue/purple/gray/dark_gray/black}、
 * {@code #RRGGBB}、{@code #AARRGGBB} 或 16 进制整数（{@code FFD700}）。</p>
 */
public final class SubtitleCommands {
    private static final Predicate<CommandSourceStack> ADMIN =
            source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());

    private SubtitleCommands() {
    }

    /** 注册独立根命令 {@code /subtitle}（{@code /vjj subtitle} 由 {@link #node()} 挂到 /vjj 下）。 */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(build("subtitle")));
        VjjNew.LOGGER.info("[VjjNew] /subtitle commands registered");
    }

    /** 供 {@code /vjj} 命令树挂载的字幕子树。 */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return build("subtitle");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requires(ADMIN)
                .then(uiSimple())
                .then(uiStyled())
                .then(Commands.literal("say")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> uiSend(context, 0xFFFFFFFF, 1.0F, 50.0, 88.0, 120)))))
                .then(worldCommands())
                .then(Commands.literal("clearui")
                        .executes(context -> clearUi(context, null))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> clearUi(context, EntityArgument.getPlayers(context, "targets")))))
                .then(Commands.literal("clearworld").executes(SubtitleCommands::clearWorld))
                .then(Commands.literal("list").executes(SubtitleCommands::list));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> uiSimple() {
        return Commands.literal("ui")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(0.0, 100.0))
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg(0.0, 100.0))
                                        .then(Commands.argument("duration", IntegerArgumentType.integer(0, 6000))
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(context -> uiSend(context, 0xFFFFFFFF, 1.0F,
                                                                DoubleArgumentType.getDouble(context, "x"),
                                                                DoubleArgumentType.getDouble(context, "y"),
                                                                IntegerArgumentType.getInteger(context, "duration"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> uiStyled() {
        return Commands.literal("uistyle")
                .then(Commands.argument("targets", EntityArgument.players())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg(0.0, 100.0))
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg(0.0, 100.0))
                                        .then(Commands.argument("duration", IntegerArgumentType.integer(0, 6000))
                                                .then(Commands.argument("color", StringArgumentType.word())
                                                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.25, 4.0))
                                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                                        .executes(context -> uiSend(context,
                                                                                parseColor(StringArgumentType.getString(context, "color")),
                                                                                (float) DoubleArgumentType.getDouble(context, "scale"),
                                                                                DoubleArgumentType.getDouble(context, "x"),
                                                                                DoubleArgumentType.getDouble(context, "y"),
                                                                                IntegerArgumentType.getInteger(context, "duration"))))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> worldCommands() {
        return Commands.literal("world")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-30000000.0, 30000000.0))
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(-2048.0, 2048.0))
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30000000.0, 30000000.0))
                                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg(-360.0, 360.0))
                                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90.0, 90.0))
                                                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.05, 8.0))
                                                                .then(Commands.argument("color", StringArgumentType.word())
                                                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                                                .executes(SubtitleCommands::worldAdd)))))))))
                .then(Commands.literal("here")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> worldHere(context, false))))
                .then(Commands.literal("wall")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> worldHere(context, true))));
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI 字幕
    // ─────────────────────────────────────────────────────────────────────

    private static int uiSend(CommandContext<CommandSourceStack> context, int color, float scale,
                              double xPercent, double yPercent, int durationTicks) throws CommandSyntaxException {
        String text = StringArgumentType.getString(context, "text");
        if (text == null || text.isBlank()) {
            context.getSource().sendFailure(Component.literal("字幕文本不能为空"));
            return 0;
        }
        float xFrac = (float) Math.max(0.0, Math.min(100.0, xPercent)) / 100.0F;
        float yFrac = (float) Math.max(0.0, Math.min(100.0, yPercent)) / 100.0F;
        SubtitleUiPayload payload = new SubtitleUiPayload(text, xFrac, yFrac, durationTicks, color, scale, true);
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int sent = 0;
        for (ServerPlayer target : targets) {
            if (VjjNet.send(target, payload)) {
                sent++;
            }
        }
        int count = sent;
        context.getSource().sendSuccess(() -> Component.literal(
                "UI 字幕已发送给 " + count + " 名玩家 @ " + String.format(Locale.ROOT, "%.0f%%/%.0f%%", xPercent, yPercent)
                        + " 时长=" + durationTicks + "t 颜色=#" + String.format(Locale.ROOT, "%08X", color)), false);
        VjjNew.LOGGER.info("[VjjNew] subtitle-ui players={} x={} y={} duration={} color={} scale={} text=\"{}\"",
                count, xFrac, yFrac, durationTicks, Integer.toHexString(color), scale, text);
        return count;
    }

    private static int clearUi(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> targets) {
        Collection<ServerPlayer> players = targets == null
                ? context.getSource().getServer().getPlayerList().getPlayers()
                : targets;
        int sent = 0;
        SubtitleClearUiPayload payload = new SubtitleClearUiPayload();
        for (ServerPlayer target : players) {
            if (VjjNet.send(target, payload)) {
                sent++;
            }
        }
        int count = sent;
        context.getSource().sendSuccess(() -> Component.literal("已清除 " + count + " 名玩家的 UI 字幕"), false);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 世界字幕
    // ─────────────────────────────────────────────────────────────────────

    private static int worldAdd(CommandContext<CommandSourceStack> context) {
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");
        double yaw = DoubleArgumentType.getDouble(context, "yaw");
        double pitch = DoubleArgumentType.getDouble(context, "pitch");
        double scale = DoubleArgumentType.getDouble(context, "scale");
        int color = parseColor(StringArgumentType.getString(context, "color"));
        String text = StringArgumentType.getString(context, "text");
        return spawnWorld(context, new Vec3(x, y, z), yaw, pitch, scale, color, text);
    }

    /**
     * {@code here}：在玩家眼前 3 格生成，正对玩家；
     * {@code wall}：沿视线 8 格内找方块，命中则贴在墙上，否则退回眼前位置。
     */
    private static int worldHere(CommandContext<CommandSourceStack> context, boolean wall) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String text = StringArgumentType.getString(context, "text");
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 pos = eye.add(look.scale(3.0));
        if (wall) {
            HitResult hit = player.pick(8.0, 0.0F, false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                Vec3 normal = blockHit.getDirection().getUnitVec3();
                pos = blockHit.getLocation().add(normal.scale(0.03));
            }
        }
        float yaw = player.getYRot() + 180.0F;
        return spawnWorld(context, pos, yaw, 0.0F, 1.0F, 0xFFFFFFFF, text);
    }

    private static int spawnWorld(CommandContext<CommandSourceStack> context, Vec3 pos,
                                  double yaw, double pitch, double scale, int color, String text) {
        if (text == null || text.isBlank()) {
            context.getSource().sendFailure(Component.literal("字幕文本不能为空"));
            return 0;
        }
        ServerLevel level = context.getSource().getLevel();
        // 单面实体（字体面本身单面）；双面显示 = 同一位置再生成一份 yaw+180 的背面副本，
        // 从正面/背面都能看到正确可读的文字。
        VjjSubtitleEntity front = createEntity(level, pos, yaw, pitch, scale, color, text);
        double normalRad = Math.toRadians(yaw);
        double nx = -Math.sin(normalRad);
        double nz = -Math.cos(normalRad);
        VjjSubtitleEntity back = createEntity(level,
                new Vec3(pos.x + nx * 0.02, pos.y, pos.z + nz * 0.02), yaw + 180.0, pitch, scale, color, text);
        int id = front.getId();
        int backId = back.getId();
        context.getSource().sendSuccess(() -> Component.literal(
                "世界字幕已生成 #" + id + "/#" + backId + " @ " + String.format(Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z)
                        + " yaw=" + String.format(Locale.ROOT, "%.0f", yaw) + " scale=" + scale
                        + " 颜色=#" + String.format(Locale.ROOT, "%08X", color) + "（含背面副本）"), false);
        VjjNew.LOGGER.info("[VjjNew] subtitle-world id={}/{} pos={},{},{} yaw={} pitch={} scale={} color={} text=\"{}\"",
                id, backId, pos.x, pos.y, pos.z, yaw, pitch, scale, Integer.toHexString(color), text);
        return 1;
    }

    private static VjjSubtitleEntity createEntity(ServerLevel level, Vec3 pos, double yaw, double pitch,
                                                  double scale, int color, String text) {
        VjjSubtitleEntity entity = new VjjSubtitleEntity(ModSubtitleEntities.SUBTITLE, level);
        entity.setPos(pos.x, pos.y, pos.z);
        entity.setYRot((float) yaw);
        entity.setXRot((float) pitch);
        entity.setSubtitle(text, color, (float) scale, true, false);
        level.addFreshEntity(entity);
        return entity;
    }

    private static int clearWorld(CommandContext<CommandSourceStack> context) {
        int removed = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            var entities = level.getEntities(ModSubtitleEntities.SUBTITLE, entity -> true);
            for (VjjSubtitleEntity entity : entities) {
                entity.discard();
                removed++;
            }
        }
        int count = removed;
        context.getSource().sendSuccess(() -> Component.literal("已清除 " + count + " 条世界字幕"), false);
        VjjNew.LOGGER.info("[VjjNew] subtitle-world-clear removed={}", count);
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        int total = 0;
        for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
            var entities = level.getEntities(ModSubtitleEntities.SUBTITLE, entity -> true);
            for (VjjSubtitleEntity entity : entities) {
                total++;
                String line = String.format(Locale.ROOT, "#%d @ %.1f,%.1f,%.1f yaw=%.0f \"%s\"",
                        entity.getId(), entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(),
                        entity.getSubtitleText());
                context.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        }
        int count = total;
        context.getSource().sendSuccess(() -> Component.literal("共 " + count + " 条世界字幕"), false);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 颜色
    // ─────────────────────────────────────────────────────────────────────

    /** 颜色名 / #RRGGBB / #AARRGGBB / 16 进制整数 → ARGB。 */
    private static int parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0xFFFFFFFF;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int named = switch (value) {
            case "white" -> 0xFFFFFFFF;
            case "black" -> 0xFF000000;
            case "dark_gray", "dark_grey", "gray", "grey" -> 0xFF808080;
            case "red" -> 0xFFFF5555;
            case "gold", "orange" -> 0xFFFFAA00;
            case "yellow" -> 0xFFFFFF55;
            case "green" -> 0xFF55FF55;
            case "aqua", "cyan" -> 0xFF55FFFF;
            case "blue" -> 0xFF5555FF;
            case "purple" -> 0xFFFF55FF;
            default -> 0;
        };
        if (named != 0) {
            return named;
        }
        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                if (hex.length() == 6) {
                    return 0xFF000000 | (int) Long.parseLong(hex, 16);
                }
                if (hex.length() == 8) {
                    return (int) Long.parseLong(hex, 16);
                }
            }
            return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException ignored) {
            return 0xFFFFFFFF;
        }
    }
}
