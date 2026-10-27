package com.rpgcam.cue;

/**
 * 镜头锚点（相机围着谁转 / 看向谁）。
 */
public enum AnchorType {
    /** 收到指令的玩家本人（单人展示、任务完成）。 */
    PLAYER,
    /** 命令/API 指定的目标实体（NPC、首领、被观察对象）。 */
    TARGET,
    /** 命令指定的固定世界坐标（场景巡游、固定机位）。 */
    POSITION
}
