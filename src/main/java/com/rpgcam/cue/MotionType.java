package com.rpgcam.cue;

/**
 * 镜头运动类型。客户端 {@code CameraMath} 在每一种 motion 下用同一组参数（距离/角度/高度）计算轨迹，
 * 差别只在“进度如何映射到角度、距离、高度”以及附加的噪声/衰减。
 */
public enum MotionType {
    /** 环绕锚点（固定距离，角度从 startAngle 扫 sweep 度）。 */
    ORBIT,
    /** 推/拉镜头（固定角度，距离从 startDist 变到 endDist）。 */
    DOLLY,
    /** 摇臂升降（固定角度与距离，高度从 startHeight 变到 endHeight）。 */
    CRANE,
    /** 手持跟拍（在 HOVER 基础上叠加低频抖动）。 */
    HANDHELD,
    /** 倒地/坠落（高度快速下降 + 俯角收束，用于死亡镜头）。 */
    DROP,
    /** 悬停微移（几乎静止的展示镜头，带极慢漂移，用于对话/奖励特写）。 */
    HOVER
}
