package com.rpganim.anim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动作采样器（纯逻辑，服务端也可用；不依赖任何客户端类）。
 * <p>把 {@link AnimDefinition} 的骨骼关键帧在给定动作内时间上采样成
 * {@code 骨骼名 -> BoneOffsets}，并乘上进入/退出混合权重。
 */
public final class AnimSampler {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private AnimSampler() {
    }

    /**
     * @param def          动作定义
     * @param elapsedTicks 从动作开始经过的 tick（客户端本地时钟）
     * @param weight       混合权重 0..1（进入/退出时 <1；<0.01 视为无偏移）
     * @return 骨骼名 -> 偏移；无偏移返回空 map（不返回 null）
     */
    public static Map<String, BoneOffsets> sample(AnimDefinition def, float elapsedTicks, float weight) {
        Map<String, BoneOffsets> result = new LinkedHashMap<>();
        if (def == null || def.bones().isEmpty()) {
            return result;
        }
        float w = Math.max(0.0F, Math.min(1.0F, weight));
        if (w <= 0.01F) {
            return result;
        }
        float t = timeline(def, elapsedTicks);
        for (BoneTrack track : def.bones().values()) {
            if (track == null || !track.hasAnyChannel()) {
                continue;
            }
            float[] rotation = null;
            if (track.rotation() != null && !track.rotation().isEmpty()) {
                float[] raw = track.rotation().sample(t);
                rotation = new float[] {
                        raw[0] * DEG_TO_RAD * w,
                        raw[1] * DEG_TO_RAD * w,
                        raw[2] * DEG_TO_RAD * w
                };
            }
            float[] position = null;
            if (track.position() != null && !track.position().isEmpty()) {
                float[] raw = track.position().sample(t);
                position = new float[] {raw[0] * w, raw[1] * w, raw[2] * w};
            }
            float[] scale = null;
            if (track.scale() != null && !track.scale().isEmpty()) {
                float[] raw = track.scale().sample(t);
                scale = new float[] {raw[0] * w, raw[1] * w, raw[2] * w};
            }
            if (rotation != null || position != null || scale != null) {
                result.put(track.bone(), new BoneOffsets(rotation, position, scale));
            }
        }
        return result;
    }

    /** 动作内时间：循环动作取模，非循环动作在末尾钳制。 */
    public static float timeline(AnimDefinition def, float elapsedTicks) {
        float duration = Math.max(1.0F, def.durationTicks());
        if (elapsedTicks <= 0.0F) {
            return 0.0F;
        }
        if (def.loop()) {
            return elapsedTicks % duration;
        }
        return Math.min(elapsedTicks, duration);
    }

    /**
     * 进入/退出混合权重：前 blendInTicks 渐入，非循环动作在末尾 blendOutTicks 渐出。
     * <p>循环动作只有在进入时渐入（持续满权重），停止时的渐出由客户端收尾逻辑负责
     * （停止时刻起把 {@code weight} 从 1 线性降到 0，时长 {@link AnimDefinition#blendOutTicks()}）。
     */
    public static float blendWeight(AnimDefinition def, float elapsedTicks) {
        float in = Math.max(1.0F, def.blendInTicks());
        float fadeIn = Math.min(1.0F, Math.max(0.0F, elapsedTicks) / in);
        if (def.loop()) {
            return fadeIn;
        }
        float out = Math.max(1.0F, def.blendOutTicks());
        float remain = Math.max(0.0F, def.durationTicks() - elapsedTicks);
        float fadeOut = Math.min(1.0F, remain / out);
        return Math.max(0.0F, Math.min(fadeIn, fadeOut));
    }

    /** 循环动作停止时的渐出权重（{@code ticksSinceStop} >= blendOutTicks 时为 0）。 */
    public static float stopWeight(AnimDefinition def, float ticksSinceStop) {
        float out = Math.max(1.0F, def.blendOutTicks());
        return Math.max(0.0F, Math.min(1.0F, 1.0F - ticksSinceStop / out));
    }

    /** 动作是否已结束（非循环动作按 durationTicks 结束；循环动作不结束）。 */
    public static boolean expired(AnimDefinition def, float elapsedTicks) {
        return !def.loop() && elapsedTicks >= def.durationTicks();
    }
}
