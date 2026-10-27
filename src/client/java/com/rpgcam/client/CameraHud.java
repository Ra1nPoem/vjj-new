package com.rpgcam.client;

import com.rpgcam.cue.CueRegistry;
import com.rpgcam.cue.CueSpec;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 过场 HUD：上下黑边、样式标题、进度条、黑场淡入淡出。
 * 由 {@code HudMixin} 在 {@code Hud.extractRenderState} 头部调用——即使原版 HUD 被隐藏，
 * 这个 overlay 仍然会画出来（它本身不是原版 HUD 组件）。
 */
public final class CameraHud {
    private static final int BAR_COLOR = 0xFF000000;
    private static final int TITLE_COLOR = 0xFFF2E6C8;
    private static final int ACCENT_COLOR = 0xFFE8C56A;

    private CameraHud() {
    }

    public static void render(GuiGraphicsExtractor graphics, CameraDirector director, DeltaTracker deltaTracker) {
        CameraDirector.PoseData pose = director.lastPose();
        if (pose == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        // 黑场淡入淡出（整屏）
        if (pose.fadeAlpha() > 0.004F) {
            int alpha = Math.min(255, Math.max(0, Math.round(pose.fadeAlpha() * 255.0F)));
            graphics.fill(0, 0, width, height, alpha << 24);
        }

        // 上下黑边
        int bar = Math.round(height * pose.letterbox());
        if (bar > 0) {
            graphics.fill(0, 0, width, bar, BAR_COLOR);
            graphics.fill(0, height - bar, width, height, BAR_COLOR);
        }

        // 样式标题（任务联动时带任务名；label 允许是翻译键，例如 questguide 的任务标题键）
        CueSpec spec = CueRegistry.get(pose.styleId());
        Component title = spec == null ? Component.literal(pose.styleId()) : Component.translatable(spec.titleKey());
        if (pose.label() != null && !pose.label().isEmpty()) {
            Component label = pose.label().indexOf('.') >= 0 && pose.label().indexOf(' ') < 0
                    ? Component.translatable(pose.label())
                    : Component.literal(pose.label());
            title = Component.empty().append(label).append(Component.literal(" · ")).append(title);
        }
        int textY = height - Math.max(bar, 10) - 16;
        if (textY < 12) {
            textY = 12;
        }
        graphics.centeredText(mc.font, title, width / 2, textY, TITLE_COLOR);

        // 进度条
        int lineWidth = Math.min(280, Math.max(120, width / 4));
        int x0 = width / 2 - lineWidth / 2;
        int y0 = textY + 12;
        graphics.fill(x0 - 1, y0 - 1, x0 + lineWidth + 1, y0 + 2, 0x66000000);
        int filled = Math.round(lineWidth * Math.clamp(pose.progress(), 0.0F, 1.0F));
        if (filled > 0) {
            graphics.fill(x0, y0, x0 + filled, y0 + 2, ACCENT_COLOR);
        }
    }
}
