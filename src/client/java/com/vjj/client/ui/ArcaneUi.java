package com.vjj.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Random;

/**
 * 奥术风格 UI 绘制工具（模仿 ArcaneVortex 的「星云/发光边框/符文」视觉语言，原创实现）。
 *
 * <p>全部用 GUI 原生 fill/fillGradient/blit/text + 程序化星点完成，没有额外着色器：
 * 背景星云、发光面板、符文装饰、渐变按钮、色板、星空。供特效工坊等界面复用。</p>
 */
public final class ArcaneUi {
    public static final Identifier TEX_NEBULA = Identifier.fromNamespaceAndPath("vjjnew", "textures/ui/nebula.png");
    public static final Identifier TEX_GLOW = Identifier.fromNamespaceAndPath("vjjnew", "textures/ui/glow.png");
    public static final Identifier TEX_RUNE = Identifier.fromNamespaceAndPath("vjjnew", "textures/ui/rune.png");
    public static final Identifier TEX_STAR = Identifier.fromNamespaceAndPath("vjjnew", "textures/ui/star.png");
    public static final Identifier TEX_SPARK = Identifier.fromNamespaceAndPath("vjjnew", "textures/ui/spark.png");

    public static final int BG_DEEP = 0xFF060310;
    public static final int BG_MID = 0xFF0C0620;
    public static final int PANEL_TOP = 0xE8140A2E;
    public static final int PANEL_BOTTOM = 0xE00A0618;
    public static final int ACCENT = 0xFFB07CFF;
    public static final int ACCENT_SOFT = 0x66B07CFF;
    public static final int GOLD = 0xFFFFD060;
    public static final int TEXT = 0xFFEDE6FF;
    public static final int TEXT_DIM = 0xFF9C8FBF;
    public static final int CYAN = 0xFF66E0FF;

    private ArcaneUi() {
    }

    /** 全屏奥术背景：深色渐变 + 两层旋转星云 + 闪烁星点。 */
    public static void background(GuiGraphicsExtractor graphics, long timeMs) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        graphics.fill(0, 0, w, h, BG_DEEP);
        graphics.fillGradient(0, 0, w, h, BG_MID, BG_DEEP);
        float drift = (timeMs % 120000L) / 120000.0F;
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(w / 2.0F, h / 2.0F);
        pose.rotate(drift * 360.0F * 0.05F);
        int nebulaSize = Math.max(w, h) * 2;
        graphics.blit(TEX_NEBULA, -nebulaSize / 2, -nebulaSize / 2, nebulaSize, nebulaSize,
                0.0F, 0.0F, 1.0F, 1.0F);
        pose.popMatrix();
        pose.pushMatrix();
        pose.translate(w * 0.7F, h * 0.3F);
        pose.rotate(-drift * 360.0F * 0.03F);
        int secondSize = (int) (Math.max(w, h) * 1.1F);
        graphics.blit(TEX_NEBULA, -secondSize / 2, -secondSize / 2, secondSize, secondSize,
                0.1F, 0.1F, 0.9F, 0.9F);
        pose.popMatrix();
        graphics.fill(0, 0, w, h, 0x66000000);
        starfield(graphics, 0x20261026L, 110);
    }

    /** 星点（闪烁）。 */
    public static void starfield(GuiGraphicsExtractor graphics, long seed, int count) {
        Random random = new Random(seed);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        long time = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            int x = random.nextInt(Math.max(1, w));
            int y = random.nextInt(Math.max(1, h));
            int size = random.nextBoolean() ? 2 : 3;
            float twinkle = (float) ((Math.sin(time * 0.0015 + i * 1.7) + 1.0) * 0.5);
            int alpha = (int) ((0.25 + 0.65 * twinkle) * 255);
            graphics.blit(TEX_STAR, x, y, size, size, 0.0F, 0.0F, 1.0F, 1.0F);
            graphics.fill(x, y, x + size, y + size, (alpha / 3) << 24 | 0xFFFFFF);
        }
    }

    /** 发光 + 渐变面板（ArcaneVortex 风格圆角/辉光的近似）。 */
    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int accent) {
        for (int i = 6; i >= 1; i--) {
            int alpha = 10 + (6 - i) * 8;
            graphics.fill(x - i, y - i, x + w + i, y + h + i, (alpha << 24) | (accent & 0x00FFFFFF));
        }
        graphics.fillGradient(x, y, x + w, y + h, PANEL_TOP, PANEL_BOTTOM);
        graphics.outline(x, y, x + w, y + h, (0xD0 << 24) | (accent & 0x00FFFFFF));
        graphics.outline(x + 1, y + 1, x + w - 1, y + h - 1, 0x30FFFFFF);
        rune(graphics, x + 10, y + h - 10, 26, System.currentTimeMillis() / 40.0F, 0.35F);
        rune(graphics, x + w - 10, y + 10, 26, -System.currentTimeMillis() / 55.0F, 0.25F);
    }

    /** 绘制旋转符文（用预生成 rune 纹理 + pose 旋转）。 */
    public static void rune(GuiGraphicsExtractor graphics, int cx, int cy, int size, float rotationDeg, float alpha) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.rotate(rotationDeg);
        graphics.blit(TEX_RUNE, -size / 2, -size / 2, size, size, 0.0F, 0.0F, 1.0F, 1.0F);
        pose.popMatrix();
    }

    /** 发光标题（大字 + 下方阴影/星点）。 */
    public static void title(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                             String text, int centerX, int y, int color) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, y);
        pose.scale(2.0F, 2.0F);
        int width = font.width(Component.literal(text));
        graphics.text(font, Component.literal(text), -width / 2, 0, color, true);
        pose.popMatrix();
    }

    /** Arcane 风格按钮（渐变底 + 发光边框 + 中心文字）。 */
    public static void titleScaled(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                                   String text, int centerX, int y, float scale, int color) {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, y);
        pose.scale(scale, scale);
        int width = font.width(Component.literal(text));
        graphics.text(font, Component.literal(text), -width / 2, 0, color, true);
        pose.popMatrix();
    }
    public static void button(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font,
                              int x, int y, int w, int h, String label,
                              boolean selected, boolean hover, int accent) {
        int border = selected ? 0xFFFFE8A0 : (hover ? 0xFFC9A2FF : (0xB0 << 24) | (accent & 0xFFFFFF));
        if (hover || selected) {
            graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, selected ? 0x50FFE8A0 : 0x30C9A2FF);
        }
        graphics.fillGradient(x, y, x + w, y + h,
                selected ? 0xF03A1D5E : (hover ? 0xF02A1550 : 0xE0140A2E),
                selected ? 0xF01D0E3A : (hover ? 0xE01A0B36 : 0xE00A0618));
        graphics.outline(x, y, x + w, y + h, border);
        String clipped = font.plainSubstrByWidth(label == null ? "" : label, Math.max(8, w - 10));
        graphics.centeredText(font, Component.literal(clipped), x + w / 2, y + (h - 8) / 2 + 1,
                selected ? 0xFFFFE8A0 : (hover ? 0xFFF2E6FF : TEXT));
    }

    /** 颜色色板（小方块 + 高亮框）。 */
    public static void swatch(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color, boolean selected) {
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, selected ? 0xFFFFFFFF : 0x40FFFFFF);
        graphics.fill(x, y, x + w, y + h, color);
        if (selected) {
            graphics.outline(x - 2, y - 2, x + w + 2, y + h + 2, 0xFFFFE8A0);
        }
    }

    /** 一条水平发光分隔线。 */
    public static void divider(GuiGraphicsExtractor graphics, int x1, int x2, int y, int accent) {
        graphics.fillGradient(x1, y, x2, y + 1, 0x00FFFFFF, (0x80 << 24) | (accent & 0xFFFFFF));
        graphics.fill(x1, y, x2, y + 1, 0x40FFFFFF);
    }

    /** 小号符文图标（面板里做“法术图标”）。 */
    public static void icon(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        graphics.fill(x, y, x + size, y + size, (0x30 << 24) | (color & 0xFFFFFF));
        graphics.outline(x, y, x + size, y + size, (0xA0 << 24) | (color & 0xFFFFFF));
        rune(graphics, x + size / 2, y + size / 2, size - 2, System.currentTimeMillis() / 60.0F, 0.9F);
    }
}
