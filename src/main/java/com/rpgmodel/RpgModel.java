package com.rpgmodel;

import com.rpgmodel.api.RpgModelApi;
import com.rpgmodel.command.RigCommands;
import com.rpgmodel.rigs.RigRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * rpgmodel 公共入口（服务端也加载）：加载 classpath 上的 rig 定义、注入 API 实现、注册 /rpgmodel 命令。
 *
 * <p>本类属于 common source set，不能 import 任何客户端类（专用服务器会 NoClassDefFoundError）。</p>
 */
public class RpgModel implements ModInitializer {
    public static final String MOD_ID = RpgModelApi.MOD_ID;
    /** 兜底版本号（与 gradle.properties 的 mod_version 一致）。 */
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("rpgmodel");

    @Override
    public void onInitialize() {
        LOGGER.info("[RpgModel] initializing (Minecraft 26.3 / Fabric) version={}", version());

        RigRegistry registry = RigRegistry.get();
        registry.loadFromClasspath();
        // 即使加载失败也要注入（API 会以 isLoaded=false / 空集合降级），保证后台 mod 不会因此崩。
        RpgModelApi.install(registry);

        LOGGER.info("[RpgModel] rigs loaded count={} bones={} rigs=[{}]",
                registry.rigCount(), registry.totalBoneCount(), registry.describeRigs());

        RigCommands.register();
    }

    /** @return 本 mod 的实际版本（fabric loader 元数据优先） */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse(VERSION);
    }
}
