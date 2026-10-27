package com.rpganim.anim;

/**
 * 过场开头触发的运镜（rpgcam 软依赖）。
 *
 * @param style          rpgcam 样式 id
 * @param at             相对过场开始的 tick
 * @param durationTicks  镜头时长（tick）
 */
public record CutsceneCamera(String style, int at, int durationTicks) {
}
