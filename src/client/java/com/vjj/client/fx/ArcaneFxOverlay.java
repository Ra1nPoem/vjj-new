package com.vjj.client.fx;

import com.vjj.client.ui.ArcaneUi;
import com.vjj.fx.FxType;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 全屏奥术特效层（模仿 ArcaneVortex 的屏幕后处理/星空/星云视觉，用 GUI 原生绘制实现）。
 *
 * <p>支持两类：</p>
 * <ul>
 *   <li>{@code FLASH}：单色闪光（快速衰减）；</li>
 *   <li>{@code COSMIC}：全屏星云 + 旋转星层 + 闪烁星点 + 暗角，持续数秒。</li>
 * </ul>
 */
public final class ArcaneFxOverlay {
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("vjjnew", "fx_overlay");
    private static final List<ScreenFx> ACTIVE = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private ArcaneFxOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, ArcaneFxOverlay::render);
    }

    public static void trigger(FxType type, int color, float scale, int durationTicks, long seed) {
        if (type == null) {
            return;
        }
        long durationMs = Math.max(120L, durationTicks * 50L);
        ACTIVE.add(new ScreenFx(type, color, Math.max(0.2F, scale), System.currentTimeMillis(), durationMs, seed));
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        long now = System.currentTimeMillis();
        Iterator<ScreenFx> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            ScreenFx fx = iterator.next();
            float progress = (now - fx.startMs) / (float) fx.durationMs;
            if (progress >= 1.0F) {
                iterator.remove();
                continue;
            }
            float alpha = progress < 0.2F ? progress / 0.2F : 1.0F - (progress - 0.2F) / 0.8F;
            alpha = Math.max(0.0F, Math.min(1.0F, alpha));
            if (fx.type == FxType.FLASH) {
                int argb = ((int) (alpha * 220) << 24) | (fx.color & 0x00FFFFFF);
                graphics.fill(0, 0, w, h, argb);
                int glowSize = (int) (Math.min(w, h) * (0.4F + progress * 0.9F));
                var pose = graphics.pose();
                pose.pushMatrix();
                pose.translate(w / 2.0F, h / 2.0F);
                graphics.blit(ArcaneUi.TEX_GLOW, -glowSize / 2, -glowSize / 2, glowSize, glowSize,
                        0.0F, 0.0F, 1.0F, 1.0F);
                pose.popMatrix();
            } else if (fx.type == FxType.COSMIC) {
                float fade = Math.min(1.0F, Math.min(progress / 0.15F, (1.0F - progress) / 0.3F));
                int tintAlpha = (int) (fade * 90);
                graphics.fill(0, 0, w, h, (tintAlpha << 24) | (fx.color & 0x00FFFFFF));
                var pose = graphics.pose();
                pose.pushMatrix();
                pose.translate(w / 2.0F, h / 2.0F);
                pose.rotate(progress * 360.0F * 0.06F * (fx.seed % 2 == 0 ? 1 : -1));
                int nebulaSize = (int) (Math.max(w, h) * 2.4F);
                graphics.blit(ArcaneUi.TEX_NEBULA, -nebulaSize / 2, -nebulaSize / 2, nebulaSize, nebulaSize,
                        0.0F, 0.0F, 1.0F, 1.0F);
                pose.popMatrix();
                Random stars = new Random(fx.seed);
                int starCount = 150;
                for (int i = 0; i < starCount; i++) {
                    int x = stars.nextInt(Math.max(1, w));
                    int y = stars.nextInt(Math.max(1, h));
                    int size = stars.nextBoolean() ? 2 : 3;
                    float twinkle = (float) ((Math.sin(now * 0.002 + i * 2.1) + 1.0) * 0.5);
                    int starAlpha = (int) (fade * (60 + twinkle * 180));
                    graphics.blit(ArcaneUi.TEX_STAR, x, y, size, size, 0.0F, 0.0F, 1.0F, 1.0F);
                    graphics.fill(x, y, x + size, y + size, (starAlpha / 2) << 24 | 0xFFFFFF);
                }
                graphics.fillGradient(0, 0, w, h / 4, 0x00000000, 0x80000000);
                graphics.fillGradient(0, h - h / 4, w, h, 0x80000000, 0x00000000);
                graphics.fillGradient(0, 0, w / 6, h, 0x90000000, 0x00000000);
                graphics.fillGradient(w - w / 6, 0, w, h, 0x00000000, 0x90000000);
            }
        }
    }

    private static final class ScreenFx {
        final FxType type;
        final int color;
        final float scale;
        final long startMs;
        final long durationMs;
        final long seed;

        ScreenFx(FxType type, int color, float scale, long startMs, long durationMs, long seed) {
            this.type = type;
            this.color = color;
            this.scale = scale;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.seed = seed;
        }
    }
}
