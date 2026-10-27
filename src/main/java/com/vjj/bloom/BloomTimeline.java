package com.vjj.bloom;

import java.util.ArrayList;
import java.util.List;

/**
 * Cascade 风格时间线（内部等价实现）。
 *
 * <p>需求要求技能演出使用 Cascade Timeline；由于当前 26.3 环境无法引入 Cascade，
 * 这里提供同构的轻量实现：阶段（start/end）、缓动、阶段回调、总进度和阶段名。
 * 后续迁移时只需把 {@link Stage#onEnter} 接到 Cascade 的 phase 回调，演出脚本不变。</p>
 */
public final class BloomTimeline {
    public enum Easing {
        LINEAR,
        SMOOTH,
        STEP
    }

    /** 一个演出阶段。 */
    public record Stage(String id, int startTick, int endTick, Easing easing, Runnable onEnter) {
    }

    private final List<Stage> stages = new ArrayList<>();
    private int tick;
    private int duration;
    private int lastStage = -1;

    public BloomTimeline() {
        this.duration = 240;
    }

    public BloomTimeline add(String id, int start, int end, Easing easing, Runnable onEnter) {
        stages.add(new Stage(id, start, end, easing, onEnter));
        duration = Math.max(duration, end);
        return this;
    }

    public void advance() {
        tick++;
        int index = currentStageIndex();
        if (index != lastStage) {
            lastStage = index;
            if (index >= 0) {
                Runnable action = stages.get(index).onEnter();
                if (action != null) {
                    action.run();
                }
            }
        }
    }

    public void reset() {
        tick = 0;
        lastStage = -1;
    }

    public void skipToEnd() {
        tick = duration;
    }

    public int tick() {
        return tick;
    }

    public int duration() {
        return duration;
    }

    public boolean finished() {
        return tick >= duration;
    }

    public float progress() {
        return duration <= 0 ? 1.0F : Math.min(1.0F, tick / (float) duration);
    }

    public String phaseName() {
        int index = currentStageIndex();
        return index < 0 ? "idle" : stages.get(index).id();
    }

    public Stage currentStage() {
        int index = currentStageIndex();
        return index < 0 ? null : stages.get(index);
    }

    private int currentStageIndex() {
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (tick >= stage.startTick() && tick <= stage.endTick()) {
                return i;
            }
        }
        return -1;
    }

    /** 阶段内缓动进度。 */
    public float easedProgress() {
        Stage stage = currentStage();
        if (stage == null) {
            return 0.0F;
        }
        int span = Math.max(1, stage.endTick() - stage.startTick());
        float t = Math.min(1.0F, Math.max(0.0F, (tick - stage.startTick()) / (float) span));
        return switch (stage.easing()) {
            case SMOOTH -> t * t * (3.0F - 2.0F * t);
            case STEP -> t < 0.5F ? 0.0F : 1.0F;
            case LINEAR -> t;
        };
    }

    /** 标准「棱镜法阵」演出：展开 → 充能 → 爆发 → 维持 → 淡出。 */
    public static BloomTimeline prismRitual() {
        BloomTimeline timeline = new BloomTimeline();
        timeline.add("deploy", 0, 18, Easing.SMOOTH, null);
        timeline.add("charge", 19, 70, Easing.SMOOTH, null);
        timeline.add("burst", 71, 112, Easing.LINEAR, null);
        timeline.add("sustain", 113, 220, Easing.SMOOTH, null);
        timeline.add("fade", 221, 260, Easing.SMOOTH, null);
        timeline.duration = 260;
        return timeline;
    }
}
