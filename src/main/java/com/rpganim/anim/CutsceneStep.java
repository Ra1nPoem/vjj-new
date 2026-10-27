package com.rpganim.anim;

/** 过场时间轴中的一个动画步骤。 */
public record CutsceneStep(int at, String animId, boolean loop) {
}
