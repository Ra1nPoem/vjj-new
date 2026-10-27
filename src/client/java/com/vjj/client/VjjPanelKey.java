package com.vjj.client;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * OP 面板快捷键（默认 <b>F6</b>）：按一下向服务端发 {@code /vjj panel}。
 *
 * <p>只有 OP 会成功打开界面（服务端 {@code VjjEditorService.openEditor} 二次校验权限）；
 * 非 OP 按键只会在客户端打一条日志。放在 Lead 的客户端入口里，与变焦键位（Z）互不干扰。</p>
 */
public final class VjjPanelKey {
    private static final String KEY_TRANSLATION = "vjj.key.panel";
    private static KeyMapping panelKey;

    private VjjPanelKey() {
    }

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("vjjnew", "panel"));
        panelKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(KEY_TRANSLATION, InputConstants.KEY_F6, category));
        VjjUiClient.LOGGER.info("[VjjNew] panel keybind registered key={}", keyName());
    }

    public static String keyName() {
        return panelKey == null ? KEY_TRANSLATION : panelKey.getName();
    }

    public static void tick(Minecraft minecraft) {
        if (panelKey == null || minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        while (panelKey.consumeClick()) {
            minecraft.getConnection().sendCommand("vjj panel");
            VjjUiClient.LOGGER.info("[VjjNew] panel-key command=\"vjj panel\" screen={}",
                    VjjUiClient.currentScreenId());
        }
    }
}
