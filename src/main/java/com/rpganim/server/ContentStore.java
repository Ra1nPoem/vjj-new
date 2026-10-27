package com.rpganim.server;

import com.rpganim.RpgAnim;
import com.rpganim.anim.AnimContent;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.anim.ContentException;
import com.rpganim.anim.CutsceneDefinition;
import com.rpgmodel.api.RpgModelApi;
import com.rpgmodel.api.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容（content.json）的加载、校验与降级中心。
 *
 * <p>加载时机：{@code ServerLifecycleEvents.SERVER_STARTING}（此时所有 mod 的 onInitialize 都已执行、
 * rpgmodel 的 rig 数据已就绪，可以安全调用 {@link RpgModelApi#validateBones(String, java.util.Collection)}）。</p>
 *
 * <p>降级策略（设计文档 §5.4 / §11）：</p>
 * <ul>
 *   <li>classpath 里没有 content.json 或解析失败 → 打 {@code [RpgAnim] content missing ...}，
 *       用 {@link AnimContent#empty()} 继续跑，命令仍注册、不崩；</li>
 *   <li>某条动作的骨骼名在 rig 里不存在 → 打 {@code anim-disabled ...} 并只禁用这一条动作；</li>
 *   <li>rpgmodel 未就绪 → 跳过校验（硬前置缺失时也不让启动炸掉）。</li>
 * </ul>
 *
 * <p>日志契约见 {@code docs/contract.md}（E2E 逐字段 grep，格式不要改）。</p>
 */
public final class ContentStore {
    private static final ContentStore INSTANCE = new ContentStore();

    private AnimContent content = AnimContent.empty();
    private boolean loaded;
    private boolean degraded;
    /** 校验失败被禁用的动作：id -> 原因（稳定顺序，便于日志）。 */
    private final Map<String, String> disabled = new LinkedHashMap<>();

    private ContentStore() {
    }

    public static ContentStore get() {
        return INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 加载 / 校验
    // ─────────────────────────────────────────────────────────────────────

    /** 服务端启动时调用一次；重复调用（单机重开世界）会重新加载并清空禁用表。 */
    public void load() {
        disabled.clear();
        try {
            AnimContent parsed = AnimContent.loadBundled();
            content = parsed;
            loaded = true;
            degraded = false;
            RpgAnim.LOGGER.info("[RpgAnim] content loaded animations={} cutscenes={} bytes={}",
                    parsed.animationCount(), parsed.cutsceneCount(), parsed.byteSize());
        } catch (ContentException exception) {
            content = AnimContent.empty();
            loaded = false;
            degraded = true;
            RpgAnim.LOGGER.warn("[RpgAnim] content missing resource={} reason={}",
                    AnimContent.RESOURCE_PATH, exception.getMessage());
            RpgAnim.LOGGER.info("[RpgAnim] content loaded animations=0 cutscenes=0 bytes=0 degraded=true");
            return;
        }
        validateRigs();
    }

    /**
     * 用 rpgmodel 的 rig 校验每条动作覆盖到的骨骼名；失败的动作用 {@link #isDisabled(String)} 单独禁用。
     * 日志行形如 {@code [RpgAnim] rig validation rig=humanoid checked=14 actions=14 unknown=0}。
     */
    private void validateRigs() {
        if (content.animations().isEmpty()) {
            RpgAnim.LOGGER.info("[RpgAnim] rig validation skipped reason=no-animations");
            return;
        }
        boolean modelReady;
        try {
            modelReady = RpgModelApi.isLoaded();
        } catch (Throwable throwable) {
            modelReady = false;
        }
        if (!modelReady) {
            RpgAnim.LOGGER.warn("[RpgAnim] rig validation skipped reason=rpgmodel-not-loaded");
            return;
        }
        int total = content.animationCount();
        Map<String, int[]> perRig = new LinkedHashMap<>();
        for (AnimDefinition definition : content.animations()) {
            int[] stats = perRig.computeIfAbsent(definition.rig(), key -> new int[]{0, 0});
            stats[0]++;
            try {
                ValidationReport report = RpgModelApi.validateBones(definition.rig(), definition.boneNames());
                stats[1] += report.unknownBones().size();
                if (!report.ok()) {
                    String unknown = String.join(",", report.unknownBones());
                    disabled.put(definition.id(), "unknown=" + unknown);
                    RpgAnim.LOGGER.warn("[RpgAnim] anim-disabled anim={} rig={} unknown={}",
                            definition.id(), definition.rig(), unknown);
                }
            } catch (Throwable throwable) {
                stats[1] += definition.boneNames().size();
                disabled.put(definition.id(), "validate-failed");
                RpgAnim.LOGGER.warn("[RpgAnim] anim-disabled anim={} rig={} reason=validate-failed error={}",
                        definition.id(), definition.rig(), throwable.toString());
            }
        }
        for (Map.Entry<String, int[]> entry : perRig.entrySet()) {
            RpgAnim.LOGGER.info("[RpgAnim] rig validation rig={} checked={} actions={} unknown={}",
                    entry.getKey(), entry.getValue()[0], total, entry.getValue()[1]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────────────────────────────

    public AnimContent content() {
        return content;
    }

    public boolean loaded() {
        return loaded;
    }

    /** 内容缺失/损坏时为 true（命令仍可用，但列表为空）。 */
    public boolean degraded() {
        return degraded;
    }

    public List<AnimDefinition> animations() {
        return content.animations();
    }

    public List<CutsceneDefinition> cutscenes() {
        return content.cutscenes();
    }

    public AnimDefinition animation(String id) {
        return content.animation(id);
    }

    public CutsceneDefinition cutscene(String id) {
        return content.cutscene(id);
    }

    public List<String> animationIds() {
        return content.animationIds();
    }

    public List<String> cutsceneIds() {
        return content.cutsceneIds();
    }

    public int animationCount() {
        return content.animationCount();
    }

    public int cutsceneCount() {
        return content.cutsceneCount();
    }

    public String rawJson() {
        return content.rawJson();
    }

    public int byteSize() {
        return content.byteSize();
    }

    /** 该动作是否因 rig 校验失败被禁用。 */
    public boolean isDisabled(String animId) {
        return animId != null && disabled.containsKey(animId);
    }

    public String disabledReason(String animId) {
        return disabled.get(animId);
    }
}
