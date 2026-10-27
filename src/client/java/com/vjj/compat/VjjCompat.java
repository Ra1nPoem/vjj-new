package com.vjj.compat;

import com.vjj.client.VjjUiClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * VJJ-NEW 1.1.0 玩家动画生态探测（EMF / ETF / Touch'n Motion / EMF Compat Core / Fresh Animations Player）。
 *
 * <p>本类是**无 EMF 类型引用**的守卫层：只用 {@link FabricLoader#isModLoaded(String)} 与资源管理器判断，
 * 因此 EMF 未安装时本类可正常加载；真正的 EMF API 调用全部隔离在 {@link EmfBridge} → {@link EmfBridgeImpl}。
 * 这样 VJJ 仍是「装不装 EMF 都能启动」：缺依赖时自动退回 1.0.0 的原生 mixin 管线。</p>
 *
 * <p>探测内容：</p>
 * <ul>
 *   <li>mod：{@code entity_model_features} / {@code entity_texture_features} / {@code touch_n_motion} /
 *       {@code emf_compat_core}（T&amp;M 的硬前置）；</li>
 *   <li>资源包：Fresh Animations: Player Extension（{@code minecraft:emf/cem/player.jem} +
 *       {@code minecraft:emf/cem/a_player_movement.jpm}）。资源包仓库的选中包 id（如
 *       {@code file/FA+Player-v1.1.zip}）作为第一优先判据（资源管理器在 reload 完成前查不到文件），
 *       资源管理器作为二次判据；两者都在首个 tick 之后才会稳定，因此 {@link #tick(Minecraft)} 会
 *       在最初若干 tick 内重试并在判定完成时补打一条 {@code compat-scan} 日志。</li>
 * </ul>
 *
 * <p>日志契约（E2E 依赖）：</p>
 * <pre>
 * [VjjNew] compat-scan emf=&lt;b&gt; etf=&lt;b&gt; tnm=&lt;b&gt; emf_core=&lt;b&gt; fa_player=&lt;b&gt; emf_api=&lt;n&gt; reason=&lt;init|fa-detected|fa-timeout|emf-ready&gt;
 * [VjjNew] anim-lane model=&lt;emf|mixin&gt; entity=&lt;id&gt;
 * </pre>
 */
public final class VjjCompat {
    public static final String EMF_ID = "entity_model_features";
    public static final String ETF_ID = "entity_texture_features";
    public static final String TNM_ID = "touch_n_motion";
    public static final String EMF_CORE_ID = "emf_compat_core";
    public static final String PUNCHY_ID = "punchy";

    private static final Logger LOGGER = VjjUiClient.LOGGER;
    /** FA Player 包探测最长重试 tick（约 10s；资源包/资源管理器在启动早期可能还没就绪）。 */
    private static final int FA_DETECT_TIMEOUT_TICKS = 200;
    private static final Identifier FA_PLAYER_JEM = Identifier.withDefaultNamespace("emf/cem/player.jem");
    private static final Identifier FA_PLAYER_MOVEMENT_JPM =
            Identifier.withDefaultNamespace("emf/cem/a_player_movement.jpm");

    private static final Set<String> LANE_LOGGED = new HashSet<>();

    private static boolean initialized;
    private static boolean emf;
    private static boolean etf;
    private static boolean tnm;
    private static boolean emfCore;
    private static boolean faPlayer;
    private static boolean punchy;
    private static String punchyVersion = "-";
    private static int emfApiVersion;
    private static boolean faResolved;
    private static int faDetectTicks;
    private static boolean emfReadyLogged;

    private VjjCompat() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────────────

    /** 客户端初始化调用一次：扫 mod（资源包探测在首个 tick 之后完成）。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        FabricLoader loader = FabricLoader.getInstance();
        emf = loader.isModLoaded(EMF_ID);
        etf = loader.isModLoaded(ETF_ID);
        tnm = loader.isModLoaded(TNM_ID);
        emfCore = loader.isModLoaded(EMF_CORE_ID);
        punchy = loader.isModLoaded(PUNCHY_ID);
        if (punchy) {
            punchyVersion = loader.getModContainer(PUNCHY_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
        }
        // 注意：这里只**只读**探测 EMF API 版本（打 emf_api=<n>），不注册 hook；
        // hook 注册延迟到首个 client tick（保证排在 Touch'n Motion 之后 → VJJ 姿态最后叠加）。
        emfApiVersion = emf ? EmfBridge.peekApiVersion() : 0;
        faPlayer = detectFaPlayer(true);
        faResolved = faPlayer;
        logScan("init");
    }

    /** 每个客户端 tick：一次性资源包探测补全（内部去重，稳定后空转）。 */
    public static void tick(Minecraft minecraft) {
        if (!initialized) {
            init();
        }
        if (minecraft == null || faResolved) {
            return;
        }
        faDetectTicks++;
        if (detectFaPlayer(true)) {
            faPlayer = true;
            faResolved = true;
            logScan("fa-detected");
            return;
        }
        if (faDetectTicks >= FA_DETECT_TIMEOUT_TICKS) {
            faResolved = true;
            logScan("fa-timeout");
        }
    }

    /** EmfBridge 首个 tick 注册完成后回调：回填 emf_api 版本并补打一条完整 scan 日志。 */
    public static void onEmfReady(int apiVersion, boolean hookRegistered) {
        emfApiVersion = apiVersion;
        if (!emfReadyLogged) {
            emfReadyLogged = true;
            logScan(hookRegistered ? "emf-ready" : "emf-failed");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Fresh Animations: Player Extension 探测
    // ─────────────────────────────────────────────────────────────────────

    private static boolean detectFaPlayer(boolean allowListScan) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        // ① 选中资源包 id（最可靠：E2E 用 file/FA+Player-v1.1.zip；资源管理器 reload 前也能读到）
        try {
            var repository = minecraft.getResourcePackRepository();
            if (repository != null) {
                for (String id : repository.getSelectedIds()) {
                    if (looksLikeFaPlayer(id)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 资源包仓库尚未就绪：交给 tick 重试
        }
        // ② 资源管理器：FA Player 的核心文件（player.jem + 动作 jpm 必须同时存在）
        try {
            var resources = minecraft.getResourceManager();
            if (resources != null) {
                boolean jem = resources.getResource(FA_PLAYER_JEM).isPresent();
                boolean movementJpm = resources.getResource(FA_PLAYER_MOVEMENT_JPM).isPresent();
                if (jem && movementJpm) {
                    return true;
                }
                if (allowListScan && !jem) {
                    // 兜底：某些资源包管理器会过滤单文件查询，列目录再判一次
                    var players = resources.listResources("emf/cem",
                            path -> path.getPath().endsWith("player.jem"));
                    if (!players.isEmpty()) {
                        var movements = resources.listResources("emf/cem",
                                path -> path.getPath().endsWith("a_player_movement.jpm"));
                        if (!movements.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 资源管理器尚未 reload 完成：交给 tick 重试
        }
        return false;
    }

    /** 资源包 id 是否像 Fresh Animations: Player Extension（file/FA+Player-v1.1.zip 等）。 */
    private static boolean looksLikeFaPlayer(String packId) {
        if (packId == null || packId.isBlank()) {
            return false;
        }
        String value = packId.toLowerCase(Locale.ROOT);
        if (value.contains("fa+player") || value.contains("fa_player") || value.contains("fa-player")) {
            return true;
        }
        return (value.contains("fresh") && value.contains("player"))
                || (value.contains("freshanimations") && value.contains("player"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 日志 / 展示
    // ─────────────────────────────────────────────────────────────────────

    /** GUI / 客户端命令用的稳定单行（{@code compat emf=... ...}）。 */
    public static String scanLine() {
        return String.format(Locale.ROOT,
                "compat emf=%b etf=%b tnm=%b emf_core=%b fa_player=%b punchy=%b emf_api=%d",
                emf, etf, tnm, emfCore, faPlayer, punchy, emfApiVersion);
    }

    private static void logScan(String reason) {
        LOGGER.info("[VjjNew] compat-scan emf={} etf={} tnm={} emf_core={} fa_player={} punchy={} punchy_version={} emf_api={} reason={}",
                emf, etf, tnm, emfCore, faPlayer, punchy, punchyVersion, emfApiVersion, reason);
    }

    /** 每条渲染管线（emf / mixin）首次使用时打一条日志，方便 E2E 判断动作走的是哪条路径。 */
    public static void logAnimLane(String lane, int entityId) {
        if (lane == null || !LANE_LOGGED.add(lane)) {
            return;
        }
        LOGGER.info("[VjjNew] anim-lane model={} entity={}", lane, entityId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询（GUI / 命令）
    // ─────────────────────────────────────────────────────────────────────

    public static boolean emf() {
        return emf;
    }

    public static boolean etf() {
        return etf;
    }

    public static boolean tnm() {
        return tnm;
    }

    public static boolean emfCore() {
        return emfCore;
    }

    public static boolean faPlayer() {
        return faPlayer;
    }

    /** Punchy（第一人称动画 FPA）是否安装：战斗系统的第一人称动画依赖它，VJJ 只做检测/桥接。 */
    public static boolean punchy() {
        return punchy;
    }

    /** Punchy 版本（未装时 "-"）。 */
    public static String punchyVersion() {
        return punchyVersion;
    }

    /** EMF API 版本（0 = 未装 EMF 或桥未就绪）。 */
    public static int emfApiVersion() {
        return emfApiVersion;
    }
}
