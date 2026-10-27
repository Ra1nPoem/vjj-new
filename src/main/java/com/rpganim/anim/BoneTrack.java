package com.rpganim.anim;

/**
 * 一根骨骼的完整动画轨道（三个通道都可为 null）。
 *
 * @param bone     骨骼名（必须与 rpgmodel rig 中的骨骼名一致）
 * @param rotation 旋转通道（度）
 * @param position 位置通道（模型像素偏移）
 * @param scale    缩放通道（加到 xScale/yScale/zScale 上）
 */
public record BoneTrack(String bone, Channel rotation, Channel position, Channel scale) {
    public boolean hasAnyChannel() {
        return (rotation != null && !rotation.isEmpty())
                || (position != null && !position.isEmpty())
                || (scale != null && !scale.isEmpty());
    }
}
