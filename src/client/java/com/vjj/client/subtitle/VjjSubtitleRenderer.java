package com.vjj.client.subtitle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.vjj.subtitle.VjjSubtitleEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * 世界字幕渲染器：把 {@link VjjSubtitleEntity} 的文本画在世界里。
 *
 * <p>满足的需求：</p>
 * <ul>
 *   <li><b>固定朝向/位置</b>：用实体自身 yaw/pitch 旋转文字平面，不朝玩家 billboard；</li>
 *   <li><b>多方位可见</b>：正面画一遍，再绕 Y 轴转 180° + X 镜像画一遍（背面文字保持可读，
 *       走到后面也能看到）；</li>
 *   <li><b>不是原版悬浮字</b>：只调用 {@code submitText}，<b>不调用 submitTextBackground</b>，
 *       所以没有任何黑色/半透明背景板；字体样式/颜色/缩放完全由命令控制；</li>
 *   <li>全亮渲染（{@code 0xF000F0}）：夜里/阴影里也看得清；可用 {@code POLYGON_OFFSET} 减轻 z-fighting。</li>
 * </ul>
 */
public class VjjSubtitleRenderer extends EntityRenderer<VjjSubtitleEntity, VjjSubtitleRenderState> {
    /** 全亮光照（Minecraft 的 LIGHTMAP 满亮度值）。 */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** 文字像素 → 方块：与 vanilla text display 相同的 0.025 方块/像素比例。 */
    private static final float PIXEL_TO_BLOCK = 0.025F;

    public VjjSubtitleRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public VjjSubtitleRenderState createRenderState() {
        return new VjjSubtitleRenderState();
    }

    @Override
    public void extractRenderState(VjjSubtitleEntity entity, VjjSubtitleRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.text = entity.getSubtitleText();
        state.color = entity.getSubtitleColor();
        state.scale = entity.getSubtitleScale();
        state.shadow = entity.hasSubtitleShadow();
        state.doubleSided = entity.isDoubleSided();
        state.yaw = entity.getYRot();
        state.pitch = entity.getXRot();
    }

    @Override
    public void submit(VjjSubtitleRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        String text = state.text;
        if (text == null || text.isBlank()) {
            return;
        }

        Font font = this.getFont();
        FormattedCharSequence sequence = Component.literal(text).getVisualOrderText();
        int width = font.width(sequence);
        float scale = Math.max(0.05F, Math.min(20.0F, state.scale));
        float x = -width / 2.0F;
        float y = -4.5F;
        poseStack.pushPose();
        // 实体 yaw=0 时文字朝南（+Z），与原版 text display 的 Rotation 语义一致。
        poseStack.rotateDegrees(Axis.YP, 180.0F - state.yaw);
        poseStack.rotateDegrees(Axis.XP, state.pitch);
        poseStack.scale(scale * PIXEL_TO_BLOCK, -scale * PIXEL_TO_BLOCK, scale * PIXEL_TO_BLOCK);
        collector.submitText(poseStack, x, y, sequence, state.shadow, Font.DisplayMode.POLYGON_OFFSET,
                FULL_BRIGHT, state.color, 0, 0);
        if (state.doubleSided) {
            // 背面：绕 Y 轴 180° 再画一遍；镜像 X 让背面的字也是正的（不是镜像字）。
            poseStack.rotateDegrees(Axis.YP, 180.0F);
            poseStack.translate(0.0F, 0.0F, 0.5F);
            collector.submitText(poseStack, x, y, sequence, state.shadow, Font.DisplayMode.POLYGON_OFFSET,
                    FULL_BRIGHT, state.color, 0, 0);
        }
        poseStack.popPose();
    }
}
