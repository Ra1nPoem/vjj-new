package com.vjj.cmd;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.vjj.VjjNew;
import com.vjj.camera.VjjCameraServer;
import com.vjj.dummy.VjjDummyServer;
import com.vjj.editor.VjjEditorService;
import com.vjj.net.VjjPayloads;
import com.vjj.scene.VjjSceneManager;
import com.vjj.store.CameraClip;
import com.vjj.store.CameraKey;
import com.vjj.store.VjjConfigStore;
import com.vjj.store.VjjSettings;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * {@code /vjj} 命令树：OP 演出控制 + 无 GUI 环境的完整功能面（E2E 也用这套命令驱动）。
 *
 * <pre>
 * /vjj help|status|data|list|save|reload|panel [tab]
 * /vjj new &lt;id&gt; [name]           创建镜头（默认 200t，含 1 个关键帧）
 * /vjj delete &lt;id&gt;               删除镜头
 * /vjj duration &lt;clip&gt; &lt;ticks&gt;   改时长
 * /vjj key add &lt;clip&gt; &lt;tick&gt;     把执行者当前机位存成关键帧（自动捕获位置/朝向，FOV=70）
 * /vjj key addat &lt;clip&gt; &lt;tick&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;yaw&gt; &lt;pitch&gt; &lt;fov&gt;
 * /vjj key del &lt;clip&gt; &lt;index&gt;
 * /vjj key list &lt;clip&gt;
 * /vjj play &lt;clip&gt; [target]      只放运镜（不锁行动/不生成假人）
 * /vjj stop [target]
 * /vjj scene &lt;clip&gt; [target] [anim]   完整场景：镜头 + 隐藏本体 + 锁行动 + 假人动画
 * /vjj scenestop [target]
 * /vjj lock &lt;on|off&gt; [target]
 * /vjj hide &lt;on|off&gt; [target]
 * /vjj zoom &lt;factor&gt; [target] [ticks]
 * /vjj anim list|play &lt;anim&gt; [target] [loop]|stop [target]
 * /vjj dummy spawn [anim] [target]|remove [target]|list|anim &lt;anim&gt; [target]
 * /vjj demo [target]              默认演示镜头 + 挥手动画（一键验收场景）
 * </pre>
 */
public final class VjjCommands {
    private static final Predicate<CommandSourceStack> ADMIN =
            source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());
    private static final SimpleCommandExceptionType NO_PLAYER =
            new SimpleCommandExceptionType(Component.literal("该命令需要玩家执行（或用 target 指定玩家）"));

    private VjjCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("vjj")
                    .executes(VjjCommands::help)
                    .then(Commands.literal("help").executes(VjjCommands::help))
                    .then(Commands.literal("panel")
                            .requires(ADMIN)
                            .executes(context -> panel(context, "hub"))
                            .then(Commands.argument("tab", StringArgumentType.word())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                            List.of("hub", "camera", "anim", "settings", "fx"), builder))
                                    .executes(context -> panel(context,
                                            StringArgumentType.getString(context, "tab")))))
                    .then(Commands.literal("status").executes(VjjCommands::status))
                    .then(Commands.literal("compat").executes(VjjCommands::compat))
                    .then(Commands.literal("data").requires(ADMIN).executes(VjjCommands::data))
                    .then(Commands.literal("list").executes(VjjCommands::list))
                    .then(Commands.literal("save").requires(ADMIN).executes(VjjCommands::save))
                    .then(Commands.literal("reload").requires(ADMIN).executes(VjjCommands::reload))
                    .then(Commands.literal("new")
                            .requires(ADMIN)
                            .then(Commands.argument("id", StringArgumentType.word())
                                    .executes(context -> newClip(context, StringArgumentType.getString(context, "id"), null))
                                    .then(Commands.argument("name", StringArgumentType.greedyString())
                                            .executes(context -> newClip(context,
                                                    StringArgumentType.getString(context, "id"),
                                                    StringArgumentType.getString(context, "name"))))))
                    .then(Commands.literal("delete")
                            .requires(ADMIN)
                            .then(Commands.argument("clip", StringArgumentType.word())
                                    .suggests(VjjCommands::suggestClips)
                                    .executes(VjjCommands::deleteClip)))
                    .then(Commands.literal("duration")
                            .requires(ADMIN)
                            .then(Commands.argument("clip", StringArgumentType.word())
                                    .suggests(VjjCommands::suggestClips)
                                    .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 6000))
                                            .executes(VjjCommands::setDuration))))
                    .then(keyCommands())
                    .then(Commands.literal("play")
                            .requires(ADMIN)
                            .then(Commands.argument("clip", StringArgumentType.word())
                                    .suggests(VjjCommands::suggestClips)
                                    .executes(context -> play(context, null))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> play(context,
                                                    EntityArgument.getPlayer(context, "target"))))))
                    .then(Commands.literal("stop")
                            .requires(ADMIN)
                            .executes(context -> stop(context, null))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(context -> stop(context,
                                            EntityArgument.getPlayer(context, "target")))))
                    .then(Commands.literal("scene")
                            .requires(ADMIN)
                            .then(Commands.argument("clip", StringArgumentType.word())
                                    .suggests(VjjCommands::suggestClips)
                                    .executes(context -> scene(context, null, null))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> scene(context,
                                                    EntityArgument.getPlayer(context, "target"), null))
                                            .then(Commands.argument("anim", StringArgumentType.word())
                                                    .suggests(VjjCommands::suggestAnims)
                                                    .executes(context -> scene(context,
                                                            EntityArgument.getPlayer(context, "target"),
                                                            StringArgumentType.getString(context, "anim")))))))
                    .then(Commands.literal("scenestop")
                            .requires(ADMIN)
                            .executes(context -> sceneStop(context, null))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(context -> sceneStop(context,
                                            EntityArgument.getPlayer(context, "target")))))
                    .then(Commands.literal("lock")
                            .requires(ADMIN)
                            .then(Commands.literal("on").executes(context -> lock(context, null, true))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> lock(context,
                                                    EntityArgument.getPlayer(context, "target"), true))))
                            .then(Commands.literal("off").executes(context -> lock(context, null, false))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> lock(context,
                                                    EntityArgument.getPlayer(context, "target"), false)))))
                    .then(Commands.literal("hide")
                            .requires(ADMIN)
                            .then(Commands.literal("on").executes(context -> hide(context, null, true))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> hide(context,
                                                    EntityArgument.getPlayer(context, "target"), true))))
                            .then(Commands.literal("off").executes(context -> hide(context, null, false))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> hide(context,
                                                    EntityArgument.getPlayer(context, "target"), false)))))
                    .then(Commands.literal("zoom")
                            .requires(ADMIN)
                            .then(Commands.argument("factor", DoubleArgumentType.doubleArg(0.1, 20.0))
                                    .executes(context -> zoom(context, null,
                                            DoubleArgumentType.getDouble(context, "factor"), 80))
                                    .then(Commands.argument("target", EntityArgument.player())
                                            .executes(context -> zoom(context,
                                                    EntityArgument.getPlayer(context, "target"),
                                                    DoubleArgumentType.getDouble(context, "factor"), 80))
                                            .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 6000))
                                                    .executes(context -> zoom(context,
                                                            EntityArgument.getPlayer(context, "target"),
                                                            DoubleArgumentType.getDouble(context, "factor"),
                                                            IntegerArgumentType.getInteger(context, "ticks")))))))
                    .then(animCommands())
                    .then(dummyCommands())
                    .then(com.vjj.subtitle.SubtitleCommands.node())
                    .then(com.vjj.fx.FxCommands.node())
                    .then(Commands.literal("demo")
                            .requires(ADMIN)
                            .executes(context -> demo(context, null))
                            .then(Commands.argument("target", EntityArgument.player())
                                    .executes(context -> demo(context,
                                            EntityArgument.getPlayer(context, "target"))))));
        });
        VjjNew.LOGGER.info("[VjjNew] /vjj commands registered");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 子命令树装配
    // ─────────────────────────────────────────────────────────────────────

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> keyCommands() {
        return Commands.literal("key")
                .then(Commands.literal("add")
                        .requires(ADMIN)
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests(VjjCommands::suggestClips)
                                .then(Commands.argument("tick", IntegerArgumentType.integer(0, 6000))
                                        .executes(VjjCommands::keyAddCapture))))
                .then(Commands.literal("addat")
                        .requires(ADMIN)
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests(VjjCommands::suggestClips)
                                .then(Commands.argument("tick", IntegerArgumentType.integer(0, 6000))
                                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("yaw",
                                                                                DoubleArgumentType.doubleArg(-360.0, 360.0))
                                                                        .then(Commands.argument("pitch",
                                                                                        DoubleArgumentType.doubleArg(-90.0, 90.0))
                                                                                .then(Commands.argument("fov",
                                                                                                DoubleArgumentType.doubleArg(1.0, 180.0))
                                                                                        .executes(VjjCommands::keyAddAt))))))))))
                .then(Commands.literal("del")
                        .requires(ADMIN)
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests(VjjCommands::suggestClips)
                                .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                        .executes(VjjCommands::keyDelete))))
                .then(Commands.literal("list")
                        .then(Commands.argument("clip", StringArgumentType.word())
                                .suggests(VjjCommands::suggestClips)
                                .executes(VjjCommands::keyList)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> animCommands() {
        return Commands.literal("anim")
                .then(Commands.literal("list").executes(context -> animList(context)))
                .then(Commands.literal("play")
                        .requires(ADMIN)
                        .then(Commands.argument("anim", StringArgumentType.word())
                                .suggests(VjjCommands::suggestAnims)
                                .executes(context -> animPlay(context, null, false))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> animPlay(context,
                                                EntityArgument.getPlayer(context, "target"), false))
                                        .then(Commands.literal("loop")
                                                .executes(context -> animPlay(context,
                                                        EntityArgument.getPlayer(context, "target"), true))))))
                .then(Commands.literal("stop")
                        .requires(ADMIN)
                        .executes(context -> animStop(context, null))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> animStop(context,
                                        EntityArgument.getPlayer(context, "target")))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> dummyCommands() {
        return Commands.literal("dummy")
                .then(Commands.literal("spawn")
                        .requires(ADMIN)
                        .executes(context -> dummySpawn(context, null, null))
                        .then(Commands.argument("anim", StringArgumentType.word())
                                .suggests(VjjCommands::suggestAnims)
                                .executes(context -> dummySpawn(context, null,
                                        StringArgumentType.getString(context, "anim")))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> dummySpawn(context,
                                                EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "anim"))))))
                .then(Commands.literal("remove")
                        .requires(ADMIN)
                        .executes(context -> dummyRemove(context, null))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> dummyRemove(context,
                                        EntityArgument.getPlayer(context, "target")))))
                .then(Commands.literal("list").executes(VjjCommands::dummyList))
                .then(Commands.literal("anim")
                        .requires(ADMIN)
                        .then(Commands.argument("anim", StringArgumentType.word())
                                .suggests(VjjCommands::suggestAnims)
                                .executes(context -> dummyAnim(context, null,
                                        StringArgumentType.getString(context, "anim")))
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(context -> dummyAnim(context,
                                                EntityArgument.getPlayer(context, "target"),
                                                StringArgumentType.getString(context, "anim"))))));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 执行器：基础
    // ─────────────────────────────────────────────────────────────────────

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String[] lines = {
                "/vjj panel [hub|camera|anim|settings]  — 打开可视化操作界面（OP）",
                "/vjj list | status | data                — 镜头列表 / 状态 / 全量数据（日志取证）",
                "/vjj new <id> | delete <id> | duration <clip> <ticks>",
                "/vjj key add <clip> <tick> | key addat ... | key del <clip> <index> | key list <clip>",
                "/vjj play <clip> [target] | stop [target]       — 只放运镜",
                "/vjj scene <clip> [target] [anim]               — 镜头 + 隐藏 + 锁行动 + 假人动画",
                "/vjj scenestop [target] | lock <on|off> [target] | hide <on|off> [target]",
                "/vjj zoom <factor> [target] [ticks]             — 强制变焦",
                "/vjj anim list | anim play <anim> [target] | anim stop [target]",
                "/vjj dummy spawn [anim] [target] | dummy remove [target] | dummy list",
                "/vjj save | reload | demo [target]",
        };
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int panel(CommandContext<CommandSourceStack> context, String tab) throws CommandSyntaxException {
        ServerPlayer player = self(context);
        boolean opened = VjjEditorService.openEditor(player, tab);
        if (!opened) {
            context.getSource().sendFailure(Component.literal("打开失败：需要 OP 权限"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("VJJ-NEW 可视化面板已打开（" + tab + "）"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        VjjConfigStore store = VjjConfigStore.get();
        source.sendSuccess(() -> Component.literal("[VJJ-NEW] clips=" + store.clips().size()
                + " settings=" + store.settings()), false);
        if (player != null) {
            source.sendSuccess(() -> Component.literal("[VJJ-NEW] " + VjjSceneManager.status(player)
                    + " locked=" + VjjCameraServer.isLocked(player)
                    + " hidden=" + VjjCameraServer.isHidden(player)
                    + " dummies=" + VjjDummyServer.countFor(player)), false);
        }
        return 1;
    }

    private static int compat(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        String tnm = versionOf(loader, "touch_n_motion");
        String emfCore = versionOf(loader, "emf_compat_core");
        String emf = versionOf(loader, "entity_model_features");
        String etf = versionOf(loader, "entity_texture_features");
        source.sendSuccess(() -> Component.literal(
                "[VJJ-NEW] 兼容检测（服务端）：touch_n_motion=" + tnm + " emf_compat_core=" + emfCore
                        + " entity_model_features=" + emf + " entity_texture_features=" + etf), false);
        source.sendSuccess(() -> Component.literal(
                "[VJJ-NEW] EMF/ETF/Touch'n Motion/Fresh Animations 是客户端动画生态；"
                        + "客户端详情见日志 [VjjNew] compat-scan 或客户端命令 /vjjc compat。"), false);
        source.sendSuccess(() -> Component.literal(
                "[VJJ-NEW] 动画管线开关：/vjj panel settings →「EMF 管线兼容 / VJJ 动作优先 / 叠加权重」；"
                        + "当前 settings=" + VjjConfigStore.get().settings()), false);
        return 1;
    }

    private static String versionOf(net.fabricmc.loader.api.FabricLoader loader, String modId) {
        return loader.getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("-");
    }

    private static int data(CommandContext<CommandSourceStack> context) {
        VjjConfigStore store = VjjConfigStore.get();
        VjjNew.LOGGER.info("[VjjNew] data clips={} settings={}",
                store.clipsJson().replace("\n", "").replace("\r", ""),
                store.settingsJson().replace("\n", "").replace("\r", ""));
        context.getSource().sendSuccess(() -> Component.literal("数据已写入服务端日志（[VjjNew] data ...）"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CameraClip> clips = VjjConfigStore.get().clips();
        source.sendSuccess(() -> Component.literal("[VJJ-NEW] 可编辑镜头 " + clips.size() + " 段："), false);
        for (CameraClip clip : clips) {
            source.sendSuccess(() -> Component.literal(" - " + clip.id + " \"" + clip.name + "\" "
                    + clip.durationTicks + "t keys=" + (clip.keys == null ? 0 : clip.keys.size())
                    + " mode=" + clip.mode), false);
        }
        return clips.size();
    }

    private static int save(CommandContext<CommandSourceStack> context) {
        boolean ok = VjjConfigStore.get().save();
        context.getSource().sendSuccess(() -> Component.literal(ok ? "配置已保存" : "保存失败，见日志"), false);
        return ok ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        VjjConfigStore.get().load();
        context.getSource().sendSuccess(() -> Component.literal("配置已重新载入：clips="
                + VjjConfigStore.get().clips().size()), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 执行器：镜头 CRUD
    // ─────────────────────────────────────────────────────────────────────

    private static int newClip(CommandContext<CommandSourceStack> context, String id, String name) {
        CameraClip clip = VjjConfigStore.get().addClip(id, name);
        context.getSource().sendSuccess(() -> Component.literal("已创建镜头 " + clip.id + "（"
                + clip.durationTicks + "t）"), false);
        return 1;
    }

    private static int deleteClip(CommandContext<CommandSourceStack> context) {
        String clipId = StringArgumentType.getString(context, "clip");
        boolean removed = VjjConfigStore.get().removeClip(clipId);
        context.getSource().sendSuccess(() -> Component.literal(removed ? "已删除 " + clipId : "镜头不存在: " + clipId),
                false);
        return removed ? 1 : 0;
    }

    private static int setDuration(CommandContext<CommandSourceStack> context) {
        String clipId = StringArgumentType.getString(context, "clip");
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        CameraClip clip = VjjConfigStore.get().mutableClip(clipId);
        if (clip == null) {
            context.getSource().sendFailure(Component.literal("镜头不存在: " + clipId));
            return 0;
        }
        clip.durationTicks = ticks;
        clip.normalize();
        VjjNew.LOGGER.info("[VjjNew] clip-duration id={} duration={}", clipId, ticks);
        context.getSource().sendSuccess(() -> Component.literal("镜头 " + clipId + " 时长 = " + ticks + "t"), false);
        return 1;
    }

    private static int keyAddCapture(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = self(context);
        String clipId = StringArgumentType.getString(context, "clip");
        int tick = IntegerArgumentType.getInteger(context, "tick");
        CameraClip clip = VjjConfigStore.get().mutableClip(clipId);
        if (clip == null) {
            context.getSource().sendFailure(Component.literal("镜头不存在: " + clipId));
            return 0;
        }
        // 服务端看不到真实相机位置，按“第三人称背后 3 格 + 抬头 0.3”模拟当前机位：
        // relative 镜头直接存成相对眼睛的偏移；world 镜头存成世界坐标（与 handleAction 的换算约定一致）。
        Vec3 eye = player.getEyePosition();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        Vec3 cameraPos = eye.subtract(look.scale(3.0)).add(0.0, 0.3, 0.0);
        return keyAdd(context, clipId, tick, cameraPos.x, cameraPos.y, cameraPos.z, yaw, pitch, 70.0F);
    }

    private static int keyAddAt(CommandContext<CommandSourceStack> context) {
        String clipId = StringArgumentType.getString(context, "clip");
        int tick = IntegerArgumentType.getInteger(context, "tick");
        return keyAdd(context, clipId, tick,
                DoubleArgumentType.getDouble(context, "x"),
                DoubleArgumentType.getDouble(context, "y"),
                DoubleArgumentType.getDouble(context, "z"),
                (float) DoubleArgumentType.getDouble(context, "yaw"),
                (float) DoubleArgumentType.getDouble(context, "pitch"),
                (float) DoubleArgumentType.getDouble(context, "fov"));
    }

    private static int keyAdd(CommandContext<CommandSourceStack> context, String clipId, int tick,
                              double x, double y, double z, float yaw, float pitch, float fov) {
        CameraClip clip = VjjConfigStore.get().mutableClip(clipId);
        if (clip == null) {
            context.getSource().sendFailure(Component.literal("镜头不存在: " + clipId));
            return 0;
        }
        CameraKey key = new CameraKey(tick, x, y, z, yaw, pitch, fov, "smooth");
        clip.keys.add(key);
        clip.normalize();
        int index = clip.keys.indexOf(key);
        VjjNew.LOGGER.info("[VjjNew] clip-key-add id={} tick={} pos={} rot={} fov={} keys={}",
                clipId, tick, String.format("%.2f,%.2f,%.2f", x, y, z),
                String.format("%.1f,%.1f", yaw, pitch), fov, clip.keys.size());
        context.getSource().sendSuccess(() -> Component.literal("已加关键帧 " + index + " @ " + tick + "t（共 "
                + clip.keys.size() + " 帧）"), false);
        return 1;
    }

    private static int keyDelete(CommandContext<CommandSourceStack> context) {
        String clipId = StringArgumentType.getString(context, "clip");
        int index = IntegerArgumentType.getInteger(context, "index");
        CameraClip clip = VjjConfigStore.get().mutableClip(clipId);
        if (clip == null || index < 0 || index >= clip.keys.size()) {
            context.getSource().sendFailure(Component.literal("镜头/关键帧不存在: " + clipId + "#" + index));
            return 0;
        }
        clip.keys.remove(index);
        VjjNew.LOGGER.info("[VjjNew] clip-key-del id={} index={} keys={}", clipId, index, clip.keys.size());
        context.getSource().sendSuccess(() -> Component.literal("已删除关键帧 " + index + "（剩 "
                + clip.keys.size() + " 帧）"), false);
        return 1;
    }

    private static int keyList(CommandContext<CommandSourceStack> context) {
        String clipId = StringArgumentType.getString(context, "clip");
        CameraClip clip = VjjConfigStore.get().clip(clipId);
        if (clip == null) {
            context.getSource().sendFailure(Component.literal("镜头不存在: " + clipId));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("[VJJ-NEW] " + clip), false);
        for (int i = 0; i < clip.keys.size(); i++) {
            CameraKey key = clip.keys.get(i);
            int index = i;
            context.getSource().sendSuccess(() -> Component.literal(" #" + index + " " + key), false);
        }
        return clip.keys.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 执行器：播放 / 场景 / 变焦 / 锁 / 隐藏
    // ─────────────────────────────────────────────────────────────────────

    private static int play(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        String clipId = StringArgumentType.getString(context, "clip");
        boolean broadcast = VjjConfigStore.get().settings().broadcastCamera;
        int duration = VjjCameraServer.playClip(actor, clipId, "command", broadcast);
        if (duration <= 0) {
            context.getSource().sendFailure(Component.literal("播放失败: " + clipId));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("播放镜头 " + clipId + " 目标="
                + actor.getGameProfile().name() + " " + duration + "t"), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        boolean stopped = VjjCameraServer.stop(actor, "command");
        context.getSource().sendSuccess(() -> Component.literal(stopped ? "镜头已停止" : "没有正在播放的镜头"), false);
        return stopped ? 1 : 0;
    }

    private static int scene(CommandContext<CommandSourceStack> context, ServerPlayer target, String anim)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        String clipId = StringArgumentType.getString(context, "clip");
        boolean ok = VjjSceneManager.play(actor, clipId, anim, VjjConfigStore.get().settings().targetMode);
        if (!ok) {
            context.getSource().sendFailure(Component.literal("场景开始失败: " + clipId));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("场景开始: " + clipId + " 目标="
                + actor.getGameProfile().name()), false);
        return 1;
    }

    private static int sceneStop(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        boolean stopped = VjjSceneManager.stop(actor, "command");
        context.getSource().sendSuccess(() -> Component.literal(stopped ? "场景已停止" : "没有正在播放的场景"), false);
        return stopped ? 1 : 0;
    }

    private static int lock(CommandContext<CommandSourceStack> context, ServerPlayer target, boolean locked)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        VjjCameraServer.setLocked(actor, locked, "command");
        context.getSource().sendSuccess(() -> Component.literal("锁行动=" + locked + " 目标="
                + actor.getGameProfile().name()), false);
        return 1;
    }

    private static int hide(CommandContext<CommandSourceStack> context, ServerPlayer target, boolean hidden)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        VjjCameraServer.setHidden(actor, hidden, "command");
        context.getSource().sendSuccess(() -> Component.literal("隐藏玩家=" + hidden + " 目标="
                + actor.getGameProfile().name()), false);
        return 1;
    }

    private static int zoom(CommandContext<CommandSourceStack> context, ServerPlayer target,
                            double factor, int ticks) throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        VjjCameraServer.requestZoom(actor, factor, ticks, "command");
        context.getSource().sendSuccess(() -> Component.literal("变焦 " + factor + "x → "
                + actor.getGameProfile().name() + "（" + ticks + "t）"), false);
        return 1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 执行器：动画 / 假人
    // ─────────────────────────────────────────────────────────────────────

    private static int animList(CommandContext<CommandSourceStack> context) {
        List<String> ids = VjjDummyServer.animationIds();
        context.getSource().sendSuccess(() -> Component.literal("[VJJ-NEW] 动作 " + ids.size() + " 个: " + ids), false);
        return ids.size();
    }

    private static int animPlay(CommandContext<CommandSourceStack> context, ServerPlayer target, boolean loop)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        String anim = StringArgumentType.getString(context, "anim");
        boolean ok = com.rpganim.api.RpgAnimApi.play(actor, anim, -1, loop ? "vjj-command-loop" : "vjj-command");
        context.getSource().sendSuccess(() -> Component.literal((ok ? "播放动作 " : "动作播放失败 ")
                + anim + " 目标=" + actor.getGameProfile().name() + (loop ? "（请求循环）" : "")), false);
        return ok ? 1 : 0;
    }

    private static int animStop(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        boolean ok = com.rpganim.api.RpgAnimApi.stop(actor);
        context.getSource().sendSuccess(() -> Component.literal(ok ? "动作已停止" : "当前没有动作"), false);
        return ok ? 1 : 0;
    }

    private static int dummySpawn(CommandContext<CommandSourceStack> context, ServerPlayer target, String anim)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        String effectiveAnim = anim == null || anim.isBlank()
                ? VjjConfigStore.get().settings().defaultAnim : anim;
        int id = VjjDummyServer.spawnForScene(actor, effectiveAnim, true);
        if (id < 0) {
            context.getSource().sendFailure(Component.literal("假人生成失败（见服务端日志）"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("假人已生成 id=" + id + " anim=" + effectiveAnim
                + " 目标=" + actor.getGameProfile().name()), false);
        return 1;
    }

    private static int dummyRemove(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        int removed = VjjDummyServer.clearScene(actor);
        context.getSource().sendSuccess(() -> Component.literal("已清除假人 " + removed + " 个"), false);
        return removed;
    }

    private static int dummyList(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("需要玩家执行"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("[VJJ-NEW] 假人: "
                + VjjDummyServer.listFor(player)), false);
        return 1;
    }

    private static int dummyAnim(CommandContext<CommandSourceStack> context, ServerPlayer target, String anim)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        boolean ok = VjjDummyServer.playAnim(-1, anim, true);
        context.getSource().sendSuccess(() -> Component.literal(ok ? "假人动作已切换: " + anim
                : "假人动作切换失败（先用 /vjj dummy list 看假人）"), false);
        return ok ? 1 : 0;
    }

    private static int demo(CommandContext<CommandSourceStack> context, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer actor = target != null ? target : self(context);
        if (VjjConfigStore.get().mutableClip("demo") == null) {
            VjjConfigStore.get().addClip("demo", "默认演示镜头");
        }
        boolean ok = VjjSceneManager.play(actor, "demo", "emote_wave", VjjConfigStore.get().settings().targetMode);
        context.getSource().sendSuccess(() -> Component.literal(ok ? "演示场景已开始（demo + emote_wave）"
                : "演示场景开始失败"), false);
        return ok ? 1 : 0;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static ServerPlayer self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            throw NO_PLAYER.create();
        }
        return player;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestClips(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(VjjConfigStore.get().clipIds(), builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestAnims(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(VjjDummyServer.animationIds(), builder);
    }

    /** 供命令/Debug 用：断言 VjjPayloads 类型存在（避免 IDE 认为 import 未用）。 */
    static boolean payloadsPresent() {
        return VjjPayloads.EditorActionPayload.TYPE != null && VjjSettings.class != null;
    }

    static MinecraftServer serverOf(CommandSourceStack source) {
        return source.getServer();
    }
}
