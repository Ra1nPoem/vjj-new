package com.rpgmodel.api;

/**
 * rig 上的挂点（例如 {@code right_hand} / {@code head_top}）。
 *
 * <p>挂点绑定到某根骨骼，并用模型像素偏移描述位置，供武器/特效等挂载点查询。</p>
 *
 * @param id 挂点 id（例如 {@code right_hand}）
 * @param bone 所属骨骼名
 * @param x 相对骨骼 pivot 的 X 偏移（模型像素）
 * @param y 相对骨骼 pivot 的 Y 偏移（模型像素）
 * @param z 相对骨骼 pivot 的 Z 偏移（模型像素）
 */
public record AttachPoint(String id, String bone, float x, float y, float z) {
}
