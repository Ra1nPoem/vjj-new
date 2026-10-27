package com.rpgcam.client;

import com.rpgcam.RpgCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;

/**
 * 客户端自测钩子（{@code -Drpgcam.autotest=on} 时启用；正常游玩零副作用）：
 * <ul>
 *   <li>进世界后打一条 in-world 日志；</li>
 *   <li>每段 cue 在 50% 进度自动截图一张 {@code rpgcam-<序号>-<style>.png}；</li>
 *   <li>截图成功/失败都打可 grep 的日志，供 E2E 断言。</li>
 * </ul>
 */
public final class CameraAutotest {
    private static final String PREFIX = "[RpgCamera-AUTOTEST] ";

    private static boolean enabled;
    private static boolean inWorldLogged;
    private static int sequence;
    private static boolean shotDone;
    private static String pendingShot;
    private static int pendingWait;

    private CameraAutotest() {
    }

    public static void register() {
        String value = System.getProperty("rpgcam.autotest", "off");
        enabled = value != null && !"off".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value);
        if (enabled) {
            RpgCamera.LOGGER.info(PREFIX + "enabled property={}", value);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void tick(Minecraft mc, CameraDirector director) {
        if (!enabled) {
            return;
        }
        if (!inWorldLogged && mc.player != null && mc.level != null) {
            inWorldLogged = true;
            log("in-world player=" + mc.player.getGameProfile().name()
                    + " pos=" + mc.player.blockPosition());
        }
        pollScreenshot(mc);
        if (director.isActive() && !shotDone
                && director.elapsedTicks() >= director.activeDurationTicks() / 2) {
            shotDone = true;
            String style = director.activeStyleId();
            requestShot(mc, String.format("rpgcam-%02d-%s.png", sequence, style));
        }
    }

    public static void onCueStart(CameraDirector director) {
        if (!enabled) {
            return;
        }
        sequence++;
        shotDone = false;
        log("cue-begin style=" + director.activeStyleId()
                + " duration=" + director.activeDurationTicks()
                + " local=" + director.isLocal());
    }

    public static void onCueEnd(CameraDirector director) {
        if (!enabled) {
            return;
        }
        log("cue-finished last=" + (pendingShot == null ? "-" : pendingShot));
    }

    private static void requestShot(Minecraft mc, String fileName) {
        if (pendingShot != null) {
            return;
        }
        File file = shotFile(mc, fileName);
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        pendingShot = fileName;
        pendingWait = 0;
        try {
            Screenshot.grab(mc.gameDirectory, fileName, mc.gameRenderer.mainRenderTarget(), 1,
                    message -> RpgCamera.LOGGER.info(PREFIX + "screenshot-callback {} {}",
                            fileName, message.getString()));
            log("shot-requested=" + fileName);
        } catch (Exception exception) {
            RpgCamera.LOGGER.error(PREFIX + "screenshot failed {}", fileName, exception);
            pendingShot = null;
        }
    }

    private static void pollScreenshot(Minecraft mc) {
        if (pendingShot == null) {
            return;
        }
        pendingWait++;
        File file = shotFile(mc, pendingShot);
        if (file.isFile() && file.length() > 0L) {
            log("shot-ok=" + pendingShot + " bytes=" + file.length()
                    + " path=" + file.getAbsolutePath());
            pendingShot = null;
        } else if (pendingWait > 100) {
            log("shot-TIMEOUT=" + pendingShot);
            pendingShot = null;
        }
    }

    private static File shotFile(Minecraft mc, String fileName) {
        return new File(new File(mc.gameDirectory, "screenshots"), fileName);
    }

    private static void log(String message) {
        RpgCamera.LOGGER.info(PREFIX + message);
    }
}
