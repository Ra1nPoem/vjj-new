package com.rpganim.interop;

import com.rpganim.RpgAnim;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/**
 * RpgAnim ↔ RpgCamera 的 soft-dependency 联动门面（**默认加载路径**）。
 *
 * <p>设计要点（照抄 questguide 的 {@code interop/QuestInterop.java} 模式）：</p>
 * <ol>
 *   <li><b>未装 rpgcam 时绝不加载 rpgcam API</b>：本类自己只 import fabric-loader 与 MC 服务端类，
 *       不 import 任何 {@code com.rpgcam.*}。真正的 API 调用被隔离在 {@link RpgCameraHook} 里，
 *       只有 {@link #isRpgCameraPresent()} 为真时才会执行到那条 {@code invokestatic}——JVM 方法
 *       惰性链接，因此未安装 rpgcam 的实例不会解析该常量池项，不会出现 {@code NoClassDefFoundError}。</li>
 *   <li><b>失败降级</b>：所有联动调用都包在 {@code try/catch(Throwable)} 里，任何 rpgcam 侧的异常
 *       只写一条 warn 日志，过场的动作时间轴完全不受影响。</li>
 *   <li><b>可关闭</b>：系统属性 {@code -Drpganim.interop.rpgcam=false}（默认 true）可关闭联动。</li>
 * </ol>
 *
 * <p>日志契约（E2E 直接 grep，不要改格式）：</p>
 * <pre>
 * [RpgAnim] interop rpgcam linked=true|false enabled=true|false
 * [RpgAnim] cutscene-camera player=&lt;name&gt; id=&lt;cutsceneId&gt; style=&lt;style&gt; result=&lt;ticks|-1&gt;
 * </pre>
 */
public final class RpgAnimInterop {
    /** 联动的 mod id。 */
    public static final String RPG_CAMERA_MOD_ID = "rpgcam";
    /** 关闭联动的系统属性：{@code -Drpganim.interop.rpgcam=false}。 */
    public static final String ENABLE_PROPERTY = "rpganim.interop.rpgcam";

    private static Boolean rpgCameraPresent;
    private static Boolean enabled;

    private RpgAnimInterop() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 初始化 / 配置
    // ─────────────────────────────────────────────────────────────────────

    /** 由 {@code RpgAnim#onInitialize()} 调用一次：探测 rpgcam 并打印可 grep 的状态日志。 */
    public static void init() {
        boolean present = isRpgCameraPresent();
        boolean on = isEnabled();
        RpgAnim.LOGGER.info("[RpgAnim] interop rpgcam linked={} enabled={}", present, on);
        if (present && !on) {
            RpgAnim.LOGGER.info("[RpgAnim] interop disabled by {}={} (camera hooks off)",
                    ENABLE_PROPERTY, System.getProperty(ENABLE_PROPERTY));
        }
    }

    /** rpgcam 是否加载（结果缓存；用 loader 元数据，不触碰 rpgcam 的任何类）。 */
    public static boolean isRpgCameraPresent() {
        if (rpgCameraPresent == null) {
            try {
                rpgCameraPresent = FabricLoader.getInstance().isModLoaded(RPG_CAMERA_MOD_ID);
            } catch (Throwable throwable) {
                RpgAnim.LOGGER.warn("[RpgAnim] interop failed to query FabricLoader for mod {}",
                        RPG_CAMERA_MOD_ID, throwable);
                rpgCameraPresent = false;
            }
        }
        return rpgCameraPresent;
    }

    /** 联动开关是否打开（默认开；系统属性只决定初始值）。 */
    public static boolean isEnabled() {
        if (enabled == null) {
            enabled = !isFalsy(System.getProperty(ENABLE_PROPERTY));
        }
        return enabled;
    }

    /** 镜头联动当前是否可用（装了 rpgcam 且没被属性关闭）。命令提示用。 */
    public static boolean isCameraAvailable() {
        return isRpgCameraPresent() && isEnabled();
    }

    private static boolean isFalsy(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        return "false".equalsIgnoreCase(value) || "0".equals(value)
                || "off".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 过场入口（CutsceneManager 调用）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 给玩家放一段过场镜头。
     *
     * @param cutsceneId    过场 id（只用于日志）
     * @param style         rpgcam 样式 id
     * @param durationTicks 镜头时长（tick）
     * @param labelKey      镜头 overlay 显示的翻译键（过场 titleKey）
     * @return 实际时长；失败返回 -1（rpgcam 不可用 / 客户端没装 / 样式不存在）
     */
    public static int playCamera(ServerPlayer player, String cutsceneId, String style,
                                 int durationTicks, String labelKey) {
        int result = -1;
        if (isCameraAvailable()) {
            try {
                result = RpgCameraHook.play(player, style, durationTicks, labelKey);
            } catch (Throwable throwable) {
                RpgAnim.LOGGER.warn("[RpgAnim] cutscene-camera failed player={} id={} style={} (cutscene continues)",
                        nameOf(player), cutsceneId, style, throwable);
                result = -1;
            }
        }
        RpgAnim.LOGGER.info("[RpgAnim] cutscene-camera player={} id={} style={} result={}",
                nameOf(player), cutsceneId, style, result);
        return result;
    }

    /** 过场被提前停止时收掉 rpgcam 镜头（失败只 warn，不影响收尾）。 */
    public static void stopCamera(ServerPlayer player) {
        if (!isCameraAvailable()) {
            return;
        }
        try {
            RpgCameraHook.stop(player);
        } catch (Throwable throwable) {
            RpgAnim.LOGGER.warn("[RpgAnim] interop camera stop failed player={} (ignored)", nameOf(player), throwable);
        }
    }

    private static String nameOf(ServerPlayer player) {
        return player == null ? "null" : player.getGameProfile().name();
    }
}
