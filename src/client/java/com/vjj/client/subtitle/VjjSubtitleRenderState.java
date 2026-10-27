package com.vjj.client.subtitle;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** 世界字幕的客户端渲染状态（从实体同步字段里拷贝出来，渲染时不再访问实体）。 */
public class VjjSubtitleRenderState extends EntityRenderState {
    public String text = "";
    public int color = 0xFFFFFFFF;
    public float scale = 1.0F;
    public boolean shadow = true;
    public boolean doubleSided = true;
    public float yaw;
    public float pitch;
}
