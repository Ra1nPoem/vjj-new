package com.vjj.client.fx;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** 奥术法阵渲染状态（颜色/缩放/种子 + 世界时间）。 */
public class ArcaneCircleRenderState extends EntityRenderState {
    public int color = 0xFF8A5CFF;
    public float scale = 1.0F;
    public long seed;
    public double time;
}
