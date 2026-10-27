package com.rpgmodel.client;

import com.rpgmodel.RpgModel;
import net.minecraft.client.Minecraft;

/**
 * 客户端骨骼覆盖层（HUD debug overlay）的开关状态 + 日志。
 *
 * <p>日志契约（E2E grep）：{@code [RpgModel] overlay on|off player=<name>}。</p>
 */
public final class OverlayState {
    private static volatile boolean enabled;

    private OverlayState() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void toggle() {
        set(!enabled);
    }

    public static void set(boolean value) {
        enabled = value;
        String player = currentPlayerName();
        RpgModel.LOGGER.info("[RpgModel] overlay {} player={}", value ? "on" : "off", player);
    }

    public static String currentPlayerName() {
        Minecraft client = Minecraft.getInstance();
        return client.player == null ? "-" : client.player.getGameProfile().name();
    }
}
