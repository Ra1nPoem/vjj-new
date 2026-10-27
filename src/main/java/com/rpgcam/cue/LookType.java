package com.rpgcam.cue;

/**
 * 镜头注视点。
 */
public enum LookType {
    /** 看向锚点实体的眼睛位置（最常用的“人物镜头”）。 */
    ANCHOR_EYE,
    /** 看向锚点实体的身体中心（略低，适合环绕/摇臂）。 */
    ANCHOR_CENTER,
    /** 看向玩家自己的眼睛（第一人称式展示）。 */
    PLAYER_EYE,
    /** 看向玩家自己的脚下（死亡/俯视镜头）。 */
    PLAYER_FEET,
    /** 保持玩家当前视线方向（不重算注视点，用于极简 overlay 镜头）。 */
    KEEP
}
