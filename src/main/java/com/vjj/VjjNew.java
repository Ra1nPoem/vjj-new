package com.vjj;

import com.vjj.camera.VjjCameraServer;
import com.vjj.cmd.VjjCommands;
import com.vjj.dummy.VjjDummyServer;
import com.vjj.editor.VjjEditorService;
import com.vjj.net.VjjNet;
import com.vjj.scene.VjjSceneManager;
import com.vjj.store.VjjConfigStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VJJ-NEW 公共入口：在同一个 jar 里整合 rpgcam（运镜）+ rpganim（动画）+ rpgmodel（骨骼模型），
 * 并加上本 mod 的新功能：可视化 OP 面板、关键帧镜头编辑器、变焦、强制镜头、锁死行动、隐藏玩家、
 * 播放动画时的玩家假人替代、动画独立板块。
 *
 * <p>初始化顺序：网络类型 → 配置载入 → 各模块 init → 命令树 → 服务端事件。</p>
 *
 * <p>日志契约：启动完成后必有一条
 * {@code [VjjNew] initialized clips=<n> anims=<n> modules=camera,dummy,scene,editor,ui provides=rpgcam,rpgmodel,rpganim}。</p>
 */
public final class VjjNew implements ModInitializer {
    public static final String MOD_ID = "vjjnew";
    public static final String VERSION = "1.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[VjjNew] initializing (Minecraft 26.3 / Fabric) version={}", VERSION);
        VjjNet.registerCommon();
        LOGGER.info("[VjjNew] network payload types registered");

        VjjConfigStore.get().load();
        VjjCameraServer.init();
        VjjDummyServer.init();
        com.vjj.subtitle.ModSubtitleEntities.init();
        com.vjj.fx.ModFxEntities.init();
        com.vjj.item.ModItems.init();
        VjjSceneManager.init();
        VjjEditorService.init();
        VjjCommands.register();
        com.vjj.subtitle.SubtitleCommands.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> LOGGER.info(
                "[VjjNew] server ready clips={} settings={}",
                VjjConfigStore.get().clips().size(), VjjConfigStore.get().settings()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            VjjSceneManager.onServerStopping(server);
            VjjCameraServer.onServerStopping(server);
            VjjDummyServer.onServerStopping(server);
            VjjConfigStore.get().save();
            LOGGER.info("[VjjNew] server stopping; config saved");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            VjjSceneManager.tick(server);
            VjjCameraServer.tick(server);
            VjjDummyServer.tick(server);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var player = handler.player;
            if (player == null) {
                return;
            }
            VjjSceneManager.onPlayerQuit(player);
            VjjCameraServer.onPlayerQuit(player);
            VjjDummyServer.onPlayerQuit(player);
            VjjEditorService.onPlayerQuit(player);
        });

        LOGGER.info("[VjjNew] initialized clips={} anims={} modules=camera,dummy,scene,editor,ui "
                        + "provides=rpgcam,rpgmodel,rpganim", VjjConfigStore.get().clips().size(),
                VjjDummyServer.animationIds().size());
    }
}
