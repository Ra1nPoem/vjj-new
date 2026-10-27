package com.vjj.compat;

import com.vjj.client.VjjUiClient;
import com.vjj.client.state.VjjEditorState;
import com.vjj.store.VjjSettings;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * EMF（Entity Model Features）兼容桥的**守卫层**：本类不含任何 EMF 类型引用，
 * 只做「EMF 是否安装 / 桥是否可用 / hook 是否注册」的判断，真正的 EMF API 调用在
 * {@link EmfBridgeImpl}（该类只在 EMF 存在时才会被类加载）。
 *
 * <p>为什么要桥：EMF + Touch'n Motion + Fresh Animations: Player Extension 环境下，
 * 玩家模型由 EMF 的动画管线驱动。EMF 在 {@code onBipedPoseCopyEnd} 里把 biped pose 应用回模型，
 * 会覆盖我们在 {@code PlayerModel#setupAnim} TAIL 叠加的骨骼偏移。桥的做法是注册一个
 * {@code EMFAnimationHook}（延迟到首个 client tick，保证排在 T&amp;M 之后），
 * 在 EMF 应用完 pose 的**最后一刻**再叠加 VJJ 动作 → VJJ 姿态真正可见。</p>
 *
 * <p>日志契约：</p>
 * <pre>
 * [VjjNew] compat-emf-hook registered api=&lt;n&gt;
 * [VjjNew] compat-emf-hook entity=&lt;id&gt; anim=&lt;id&gt; lane=emf
 * [VjjNew] compat-emf-init api=&lt;n&gt; hook=&lt;b&gt;
 * [VjjNew] compat-emf-fail reason=&lt;...&gt;（只打一次）
 * </pre>
 */
public final class EmfBridge {
    private static final Logger LOGGER = VjjUiClient.LOGGER;

    /** 注册尝试只做一次（首个 client tick）。 */
    private static boolean attempted;
    private static boolean available;
    private static boolean hookRegistered;
    /** api 版本（守卫层缓存；{@link #apiVersion()} 不触发初始化/注册）。 */
    private static int apiVersion;
    private static boolean failureLogged;

    private EmfBridge() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 每个 client tick 调用；首次调用时尝试初始化并注册 EMF hook（之后空转）。
     *
     * <p>延迟到首个 tick 的原因：EMF 按注册顺序调用 hook，Touch'n Motion 在它自己的
     * client init 里注册；VJJ 在首个 tick 注册 ⇒ 一定排在 T&amp;M 之后 ⇒ VJJ 姿态最后叠加、优先可见。</p>
     */
    public static void tick(Minecraft minecraft) {
        if (attempted) {
            return;
        }
        init();
    }

    /** 一次性初始化：EMF 未装则直接禁用（不需要加载 {@link EmfBridgeImpl}）。 */
    public static void init() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!FabricLoader.getInstance().isModLoaded(VjjCompat.EMF_ID)) {
            LOGGER.info("[VjjNew] compat-emf disabled reason=emf-missing");
            return;
        }
        try {
            apiVersion = EmfBridgeImpl.apiVersion();
            available = true;
            hookRegistered = EmfBridgeImpl.init();
            LOGGER.info("[VjjNew] compat-emf-init api={} hook={}", apiVersion, hookRegistered);
        } catch (Throwable throwable) {
            available = false;
            hookRegistered = false;
            logFailure(throwable);
        }
        VjjCompat.onEmfReady(apiVersion, hookRegistered);
    }

    /**
     * 只读地探测 EMF API 版本（不注册 hook、不依赖首个 tick）。
     * 供 {@link VjjCompat#init()} 在客户端初始化时就打出 {@code emf_api=<n>}；
     * 真正的 hook 注册仍在首个 client tick（{@link #tick(Minecraft)}）。
     */
    public static int peekApiVersion() {
        if (apiVersion > 0) {
            return apiVersion;
        }
        if (!FabricLoader.getInstance().isModLoaded(VjjCompat.EMF_ID)) {
            return 0;
        }
        try {
            apiVersion = EmfBridgeImpl.apiVersion();
            available = true;
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
        return apiVersion;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────────────────────────────

    /** EMF 安装且 API 可调用（不代表 hook 注册成功）。 */
    public static boolean isAvailable() {
        return available;
    }

    public static boolean isHookRegistered() {
        return hookRegistered;
    }

    /** EMF API 版本（0 = 未装 EMF / 桥未初始化）。不会触发注册。 */
    public static int apiVersion() {
        return available ? apiVersion : 0;
    }

    /**
     * 该模型是否由 EMF 动画驱动（= 需要走 hook 管线，跳过原生 mixin 叠加，避免被 EMF pose 覆盖/重复叠加）。
     *
     * <p>EMF 未装、桥未就绪、hook 注册失败、或用户在设置里关掉 EMF 兼容时一律返回 {@code false}
     * （退回 1.0.0 的原生 mixin 路径）。</p>
     */
    public static boolean isModelEmfAnimated(Object model) {
        if (!available || !hookRegistered || !emfBridgeEnabled()) {
            return false;
        }
        try {
            return EmfBridgeImpl.isModelEmfAnimated(model);
        } catch (Throwable throwable) {
            logFailure(throwable);
            return false;
        }
    }

    /** GUI / 客户端命令用的一行状态：当前动作走 EMF 管线还是原生 mixin。 */
    public static String statusLine() {
        VjjSettings settings = settingsOrNull();
        boolean enabled = settings == null || settings.emfBridge;
        if (!VjjCompat.emf()) {
            return "原生 mixin（EMF 未装）";
        }
        if (!enabled) {
            return "原生 mixin（EMF 兼容已关闭）";
        }
        if (hookRegistered) {
            return String.format(Locale.ROOT, "EMF 管线（late-pose/on/api=%d/权重=%.2f）",
                    apiVersion, overlayWeight());
        }
        if (!attempted) {
            return String.format(Locale.ROOT, "EMF 已检测（hook 待注册/api=%d）", apiVersion);
        }
        if (available) {
            return "原生 mixin（EMF hook 注册失败）";
        }
        return "原生 mixin（EMF 桥未初始化）";
    }

    /** 把当前状态写进日志（`/vjjc compat` 之外的诊断入口）。 */
    public static void logStatus() {
        LOGGER.info("[VjjNew] compat-emf-status available={} hook={} api={} line=\"{}\"",
                available, hookRegistered, apiVersion, statusLine());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 设置 / 内部
    // ─────────────────────────────────────────────────────────────────────

    /** 设置里的 EMF 兼容开关（读客户端面板状态；取不到时默认开）。 */
    static boolean emfBridgeEnabled() {
        VjjSettings settings = settingsOrNull();
        return settings == null || settings.emfBridge;
    }

    /** 设置里的叠加权重（0.2–1.0；取不到时 1.0）。 */
    static float overlayWeight() {
        VjjSettings settings = settingsOrNull();
        if (settings == null) {
            return 1.0F;
        }
        return (float) Math.max(0.2, Math.min(1.0, settings.overlayWeight));
    }

    static VjjSettings settingsOrNull() {
        try {
            VjjEditorState state = VjjEditorState.get();
            return state == null ? null : state.settings();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 末层姿态注入（{@link EmfLatePose}）异常：一次性记录，避免刷屏。 */
    public static void logLateFailure(Throwable throwable) {
        logFailure(throwable);
    }

    private static void logFailure(Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        LOGGER.warn("[VjjNew] compat-emf-fail reason=exception 已自动回退原生 mixin 管线", throwable);
    }
}
