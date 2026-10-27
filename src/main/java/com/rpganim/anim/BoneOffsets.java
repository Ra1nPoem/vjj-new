package com.rpganim.anim;

/**
 * 某一时刻一根骨骼的最终偏移量（已乘混合权重）。
 * <p>rotation 是**弧度**（直接加进 {@code ModelPart.xRot/yRot/zRot}），
 * position 是模型像素（直接加进 {@code x/y/z}），scale 是相加量（加进 {@code xScale/yScale/zScale}）。
 */
public record BoneOffsets(float[] rotationRad, float[] position, float[] scale) {
    public static final BoneOffsets ZERO = new BoneOffsets(
            new float[] {0.0F, 0.0F, 0.0F}, new float[] {0.0F, 0.0F, 0.0F}, new float[] {0.0F, 0.0F, 0.0F});

    public boolean hasRotation() {
        return rotationRad != null && (rotationRad[0] != 0.0F || rotationRad[1] != 0.0F || rotationRad[2] != 0.0F);
    }

    public boolean hasPosition() {
        return position != null && (position[0] != 0.0F || position[1] != 0.0F || position[2] != 0.0F);
    }

    public boolean hasScale() {
        return scale != null && (scale[0] != 0.0F || scale[1] != 0.0F || scale[2] != 0.0F);
    }
}
