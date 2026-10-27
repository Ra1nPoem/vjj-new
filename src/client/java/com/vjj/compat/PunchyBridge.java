package com.vjj.compat;

import com.vjj.client.VjjUiClient;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Punchy（第一人称动画 / FPA）兼容桥——**无 Punchy 类型引用**的守卫层。
 *
 * <p>Punchy 是战斗系统第一人称动画的基础（v2.8g / MC 26.3 / 客户端 mod）：
 * 它接管第一人称手臂与手持物的攻击、挖掘、使用、装填、拉弓等动作。VJJ-NEW 不重复实现这些动画，
 * 只负责：</p>
 * <ul>
 *   <li>检测 Punchy 是否安装、版本、是否在配置里启用（{@code /vjjc compat}、设置页、日志）；</li>
 *   <li>Punchy 未装时 VJJ 行为完全不变（第一人称保持原版）；</li>
 *   <li>给后续战斗系统暴露最小桥接：{@link #isLoaded()} / {@link #isEnabled()} /
 *       {@link #playThirdPerson(String)}（调用 Punchy 的第三人称手动动画，用于假人/被观察者）。</li>
 * </ul>
 *
 * <p>真正的 Punchy API 调用隔离在 {@link PunchyBridgeImpl}，只有确认 {@code punchy} 已加载才会类加载，
 * 避免 Punchy 未安装时出现 NoClassDefFoundError（与 EMF 桥同样的守卫模式）。</p>
 */
public final class PunchyBridge {
    private static final Logger LOGGER = VjjUiClient.LOGGER;

    private static boolean attempted;
    private static boolean available;
    private static boolean enabled;
    private static String version = "-";

    private PunchyBridge() {
    }

    /** 每个客户端 tick 调用：首个 tick 完成初始化（此时所有 mod 的 client 初始化都已完成）。 */
    public static void tick(Minecraft minecraft) {
        init();
    }

    private static synchronized void init() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!VjjCompat.punchy()) {
            return;
        }
        version = VjjCompat.punchyVersion();
        try {
            PunchyBridgeImpl.init();
            enabled = PunchyBridgeImpl.isEnabled();
            available = true;
            LOGGER.info("[VjjNew] compat-punchy loaded version={} enabled={}", version, enabled);
        } catch (Throwable throwable) {
            LOGGER.warn("[VjjNew] compat-punchy-fail version={} reason={}", version, throwable.toString());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询 / 展示
    // ─────────────────────────────────────────────────────────────────────

    public static boolean isLoaded() {
        init();
        return available;
    }

    /** Punchy 配置里的总开关（玩家可以在 Punchy 配置界面关闭；关闭时 VJJ 不假设第一人称动画存在）。 */
    public static boolean isEnabled() {
        if (!isLoaded()) {
            return false;
        }
        try {
            enabled = PunchyBridgeImpl.isEnabled();
        } catch (Throwable throwable) {
            LOGGER.warn("[VjjNew] compat-punchy-query-fail reason={}", throwable.toString());
        }
        return enabled;
    }

    public static String version() {
        init();
        return version;
    }

    /** 设置页第二行用短状态（不占太多宽度）。 */
    public static String shortStatus() {
        if (!isLoaded()) {
            return "Punchy 未装";
        }
        return String.format(Locale.ROOT, "Punchy %s%s", version, isEnabled() ? "（开）" : "（关）");
    }

    /** {@code /vjjc firstperson} 用的完整状态行。 */
    public static String statusLine() {
        if (!isLoaded()) {
            return "firstperson punchy=false version=- enabled=false";
        }
        return String.format(Locale.ROOT, "firstperson punchy=true version=%s enabled=%b", version, isEnabled());
    }

    /**
     * 给后续战斗系统/假人用的桥：让 Punchy 播放一段**第三人称**手动动画。
     *
     * <p>第一人称动画（攻击/挖掘/使用等）由 Punchy 自己根据玩家动作自动播放，VJJ 不需要、也不应该插手；
     * 将来 VJJ 战斗系统要加新的第一人称动作时，推荐用 Punchy 的资源包动画格式追加 clip，
     * 需要“让其他玩家/假人播第三人称动作”时再调本方法。</p>
     *
     * @return 是否成功提交（Punchy 未装 / 桥不可用时返回 false）
     */
    public static boolean playThirdPerson(String clip) {
        if (!isLoaded() || !isEnabled() || clip == null || clip.isBlank()) {
            return false;
        }
        try {
            return PunchyBridgeImpl.playThirdPerson(clip);
        } catch (Throwable throwable) {
            LOGGER.warn("[VjjNew] compat-punchy-play-fail clip={} reason={}", clip, throwable.toString());
            return false;
        }
    }
}
