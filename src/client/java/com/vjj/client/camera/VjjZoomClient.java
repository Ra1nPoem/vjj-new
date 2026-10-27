package com.vjj.client.camera;

import com.mojang.blaze3d.platform.InputConstants;
import com.vjj.client.VjjUiClient;
import com.vjj.client.state.VjjEditorState;
import com.vjj.net.VjjPayloads;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * 客户端变焦模块（按住变焦键 + 滚轮调倍率 + 服务端强制变焦）。<b>camera-dev 实现。</b>
 *
 * <p>规则（docs/VJJ-DESIGN.md §3.1）：</p>
 * <ul>
 *   <li>{@link #registerKeybind()} 注册键位（默认 <b>Z 按住变焦</b>，滚轮改倍率，倍率 1.0–20.0，
 *       初值取 {@code VjjEditorState.settings().zoomFactor}）；</li>
 *   <li>按住变焦时 FOV 压到 {@code baseFov / 倍率}（FOV mixin 在 {@code CameraDirector.modifyFov} 之后调用
 *       {@link #modifyFov(float)}）；</li>
 *   <li>服务端 {@link VjjPayloads.ZoomPayload}：{@code factor/duration}，{@code duration<=0} 常驻；
 *       持久的按键变焦与强制变焦取“更强的一个”（偏离 1.0 更多的一方）。</li>
 * </ul>
 *
 * <p>日志契约（E2E grep）：</p>
 * <pre>
 * [VjjNew] zoom-receive factor=&lt;f&gt; duration=&lt;n&gt; reason=&lt;...&gt;
 * [VjjNew] zoom-client factor=&lt;f&gt; mode=&lt;key|forced|off&gt;     （变化时）
 * [VjjNew] zoom-scroll factor=&lt;f&gt; mode=key                     （滚轮时）
 * </pre>
 */
public final class VjjZoomClient {
    /** 键位翻译键（lang 由公共层提供：vjj.key.zoom）。 */
    public static final String KEY_TRANSLATION = "vjj.key.zoom";
    private static final double MIN_FACTOR = 1.0;
    private static final double MAX_FACTOR = 20.0;
    /** 滚轮一格改变倍率的比例。 */
    private static final double SCROLL_STEP = 1.12;

    private static KeyMapping zoomKey;
    private static KeyMapping.Category category;

    /** 按住变焦键。 */
    private static boolean keyZooming;
    /** 按键变焦的倍率（滚轮可调，初值来自配置）。 */
    private static double keyFactor = 4.0;
    private static boolean keyFactorInitialized;

    /** 服务端强制变焦。 */
    private static boolean forcedActive;
    private static boolean forcedTimed;
    private static int forcedRemainingTicks;
    private static double forcedFactor = 1.0;

    /** 日志节流：只在“倍率或模式变化”时打 zoom-client。 */
    private static double loggedFactor = 1.0;
    private static String loggedMode = "off";

    private VjjZoomClient() {
    }

    public static void registerKeybind() {
        category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vjjnew", "main"));
        zoomKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(KEY_TRANSLATION, InputConstants.KEY_Z, category));
        keyFactor = defaultFactor();
        keyFactorInitialized = true;
        VjjUiClient.LOGGER.info("[VjjNew] zoom-client keybind registered key={} default_factor={} range={}-{}",
                zoomKey.getName(), fmt(keyFactor), fmt(MIN_FACTOR), fmt(MAX_FACTOR));
    }

    /** 键位显示名（启动日志用）。 */
    public static String keyName() {
        return zoomKey == null ? KEY_TRANSLATION : zoomKey.getName();
    }

    /** 服务端强制变焦。 */
    public static void onZoom(VjjPayloads.ZoomPayload payload) {
        if (payload == null) {
            return;
        }
        VjjUiClient.LOGGER.info("[VjjNew] zoom-receive factor={} duration={} reason={}",
                payload.factor(), payload.durationTicks(), payload.reason());
        double factor = Math.clamp(payload.factor(), 0.1, MAX_FACTOR);
        if (Math.abs(factor - 1.0) < 1.0E-3) {
            forcedActive = false;
            forcedTimed = false;
            forcedRemainingTicks = 0;
            forcedFactor = 1.0;
        } else {
            forcedActive = true;
            forcedFactor = factor;
            if (payload.durationTicks() > 0) {
                forcedTimed = true;
                forcedRemainingTicks = payload.durationTicks();
            } else {
                forcedTimed = false;
                forcedRemainingTicks = 0;
            }
        }
        maybeLog();
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft == null || zoomKey == null) {
            return;
        }
        if (!keyFactorInitialized) {
            keyFactor = defaultFactor();
            keyFactorInitialized = true;
        }
        boolean wantKeyZoom = zoomKey.isDown()
                && minecraft.player != null
                && minecraft.level != null
                && minecraft.gui.screen() == null
                && VjjEditorState.get().settings().zoomEnabled;
        if (wantKeyZoom != keyZooming) {
            keyZooming = wantKeyZoom;
            maybeLog();
        }
        if (forcedActive && forcedTimed) {
            if (forcedRemainingTicks > 0) {
                forcedRemainingTicks--;
            }
            if (forcedRemainingTicks <= 0) {
                forcedActive = false;
                forcedTimed = false;
                forcedFactor = 1.0;
                maybeLog();
            }
        }
        maybeLog();
    }

    /** 滚轮：按住变焦键时调倍率（由 MouseHandler mixin 调用）。返回 true 表示已消费这次滚动。 */
    public static boolean handleScroll(double yOffset) {
        if (yOffset == 0.0 || !keyZooming) {
            return false;
        }
        if (!VjjEditorState.get().settings().zoomEnabled) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() != null) {
            return false;
        }
        keyFactor = Math.clamp(yOffset > 0.0 ? keyFactor * SCROLL_STEP : keyFactor / SCROLL_STEP,
                MIN_FACTOR, MAX_FACTOR);
        VjjUiClient.LOGGER.info("[VjjNew] zoom-scroll factor={} mode=key", fmt(keyFactor));
        maybeLog();
        return true;
    }

    public static void reset() {
        keyZooming = false;
        forcedActive = false;
        forcedTimed = false;
        forcedRemainingTicks = 0;
        forcedFactor = 1.0;
        loggedFactor = 1.0;
        loggedMode = "off";
        keyFactorInitialized = false;
    }

    public static boolean isZooming() {
        return Math.abs(effectiveFactor() - 1.0) > 1.0E-3;
    }

    /** 当前生效倍率（按键变焦与强制变焦取更强的一个）。 */
    public static double currentFactor() {
        return effectiveFactor();
    }

    /** 给 FOV mixin 用：返回最终 FOV（没有变焦时原样返回）。 */
    public static float modifyFov(float vanillaFov) {
        double effective = effectiveFactor();
        if (Math.abs(effective - 1.0) < 1.0E-3) {
            return vanillaFov;
        }
        float zoomed = (float) (vanillaFov / effective);
        // FOV 合法性兜底（避免极端倍率/数值抖动产生非法值）
        return Math.clamp(zoomed, 1.0F, 179.0F);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────────────

    private static double effectiveFactor() {
        double effective = 1.0;
        if (keyZooming) {
            effective = keyFactor;
        }
        if (forcedActive) {
            double forced = Math.clamp(forcedFactor, 0.1, MAX_FACTOR);
            // “更强”= 相对 1.0 的偏离更大（放大看 factor 更大，缩小看 factor 更小）
            if (Math.abs(Math.log(forced)) > Math.abs(Math.log(effective))) {
                effective = forced;
            }
        }
        return Math.clamp(effective, 0.1, MAX_FACTOR);
    }

    private static String mode() {
        if (forcedActive) {
            return "forced";
        }
        if (keyZooming) {
            return "key";
        }
        return "off";
    }

    private static void maybeLog() {
        double effective = effectiveFactor();
        String mode = mode();
        if (Math.abs(effective - loggedFactor) > 0.01 || !mode.equals(loggedMode)) {
            loggedFactor = effective;
            loggedMode = mode;
            VjjUiClient.LOGGER.info("[VjjNew] zoom-client factor={} mode={}", fmt(effective), mode);
        }
    }

    private static double defaultFactor() {
        double configured = VjjEditorState.get().settings().zoomFactor;
        return Math.clamp(configured, MIN_FACTOR, MAX_FACTOR);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
