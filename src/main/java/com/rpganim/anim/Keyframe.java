package com.rpganim.anim;

/**
 * 单个关键帧：在 {@code tick} 时刻的 3 分量偏移 + 缓动方式。
 * <p>旋转单位是**度**（采样后转弧度），位置单位是模型像素（1 = 1/16 方块）。
 *
 * @param tick 时间点（tick，允许小数）
 * @param x    第一分量
 * @param y    第二分量
 * @param z    第三分量
 * @param ease 缓动：{@code linear}（默认）/ {@code smooth}（smoothstep）
 */
public record Keyframe(float tick, float x, float y, float z, String ease) {
    public static final String LINEAR = "linear";
    public static final String SMOOTH = "smooth";

    public Keyframe {
        if (ease == null || ease.isEmpty()) {
            ease = LINEAR;
        }
    }

    public Keyframe(float tick, float x, float y, float z) {
        this(tick, x, y, z, LINEAR);
    }

    public boolean smooth() {
        return SMOOTH.equalsIgnoreCase(ease);
    }

    /** 段内插值参数 {@code t}（0..1）→ 缓动后参数。 */
    public float ease(float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        return smooth() ? clamped * clamped * (3.0F - 2.0F * clamped) : clamped;
    }
}
