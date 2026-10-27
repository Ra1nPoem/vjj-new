package com.rpganim;

import com.rpganim.command.AnimCommands;
import com.rpganim.command.CutsceneCommands;
import com.rpganim.cutscene.CutsceneManager;
import com.rpganim.interop.RpgAnimInterop;
import com.rpganim.net.NetRegistration;
import com.rpganim.net.ServerNetworking;
import com.rpganim.server.AnimServerManager;
import com.rpganim.server.CombatTriggers;
import com.rpganim.server.ContentStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RpgAnim 公共入口（专用服务器与客户端共用的那一半）。
 * 客户端入口在 client source set 的 {@code RpgAnimClient}（split source sets，服务端不会加载）。
 *
 * <p>初始化顺序（日志也按这个顺序，见 docs/contract.md）：</p>
 * <ol>
 *   <li>payload 类型注册（双端 common，26.3 的 {@code clientboundPlay/serverboundPlay}）；</li>
 *   <li>C2S 接收器（EmoteRequest / Done）与 S2C 发包封装；</li>
 *   <li>服务端生命周期：SERVER_STARTING 加载内容、JOIN 同步整包；</li>
 *   <li>动作调度器 / 过场管理器 / 战斗触发；</li>
 *   <li>/anim、/cutscene 命令树；</li>
 *   <li>rpgcam 软依赖探测。</li>
 * </ol>
 */
public class RpgAnim implements ModInitializer {
    public static final String MOD_ID = "rpganim";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[RpgAnim] initializing (Minecraft 26.3 / Fabric) version={}", VERSION);

        NetRegistration.registerPayloadTypes();
        LOGGER.info("[RpgAnim] network payloads registered");
        ServerNetworking.registerReceivers();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> ContentStore.get().load());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> ServerNetworking.sendSync(handler.player)));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                LOGGER.info("[RpgAnim] server stopping; animations={} cutscenes={}",
                        ContentStore.get().animationCount(), ContentStore.get().cutsceneCount()));

        AnimServerManager.get().registerEvents();
        CutsceneManager.get().registerEvents();
        CombatTriggers.get().registerEvents();

        AnimCommands.register();
        CutsceneCommands.register();
        RpgAnimInterop.init();

        LOGGER.info("[RpgAnim] initialized managers=anim,cutscene,combat commands=anim,cutscene");
    }
}
