package com.vjj.client.bloom;

import com.vjj.bloom.BloomConfig;
import com.vjj.bloom.BloomTimeline;
import com.vjj.fx.ArcaneCircleEntity;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.awt.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Random;

/**
 * BloomSystem 风格 HUD：三角渐变面板 + 彩虹发光文字 + 边缘星光，展示棱镜法阵时间线。
 *
 * <p>对应需求「屏幕空间 HUD 层」：发光悬浮文字/标识、三角渐变 UI 面板、边缘星光粒子。
 * 当前用 GUI 原生 fill/text 做近似（外发光用多层偏移绘制），后续接 Veil 发光通道 + 描边 shader。</p>
 */
public final class BloomHud {
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("vjjnew", "bloom_hud");
    private static final Random RANDOM = new Random(0xB1005L);
    private static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");

    private static BloomTimeline timeline;
    private static int lingerTicks;
    private static int lastCircleId = -1;
    private static float appear;

    private BloomHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, BloomHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!BloomConfig.enabled || !BloomConfig.hudEnabled) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.font == null) {
            return;
        }
        ArcaneCircleEntity circle = nearbyCircle(minecraft);

        if (circle != null) {
            if (circle.getId() != lastCircleId) {
                lastCircleId = circle.getId();
                timeline = BloomTimeline.prismRitual();
                lingerTicks = 40;
            }
            if (timeline == null) {
                timeline = BloomTimeline.prismRitual();
            }
            if (!timeline.finished()) {
                timeline.advance();
            }
            appear = Math.min(1.0F, appear + 0.08F);
        } else if (timeline != null && lingerTicks > 0) {
            lingerTicks--;
            if (!timeline.finished()) {
                timeline.advance();
            }
            appear = Math.min(1.0F, appear + 0.04F);
        } else {
            timeline = null;
            lastCircleId = -1;
            appear = Math.max(0.0F, appear - 0.08F);
            if (appear <= 0.001F) {
                return;
            }
        }
        if (timeline == null) {
            return;
        }
        float alpha = appear * (1.0F - Math.max(0.0F, (timeline.tick() - 220) / 40.0F));
        alpha = Math.max(0.0F, Math.min(1.0F, alpha));
        renderPanel(graphics, minecraft, timeline, alpha);
    }

    private static ArcaneCircleEntity nearbyCircle(Minecraft minecraft) {
        AABB area = minecraft.player.getBoundingBox().inflate(48.0);
        List<ArcaneCircleEntity> circles = minecraft.level.getEntitiesOfClass(ArcaneCircleEntity.class, area);
        ArcaneCircleEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ArcaneCircleEntity circle : circles) {
            double distance = circle.distanceToSqr(minecraft.player);
            if (distance < best) {
                best = distance;
                nearest = circle;
            }
        }
        return nearest;
    }

    private static void renderPanel(GuiGraphicsExtractor g, Minecraft minecraft, BloomTimeline timeline, float alpha) {
        int width = g.guiWidth();
        int height = g.guiHeight();
        long now = System.currentTimeMillis();
        int panelW = Math.min(250, width / 2);
        int panelH = 92;
        int x = 18;
        int y = Math.max(24, height / 2 - 38);


        // 三角形渐变面板（用水平条近似：从底部宽边到顶部顶点收窄）
        for (int i = 0; i < panelH; i++) {
            float t = i / (float) panelH;
            int half = (int) (panelW * 0.5F * (0.12F + 0.88F * t));
            int cx = x + panelW / 2;
            int argb = lerpColor(0xFF120826, 0xFF4A2A8A, t);
            int a = (int) (105 * alpha);
            g.fill(cx - half, y + i, cx + half, y + i + 1, ((a & 0xFF) << 24) | (argb & 0x00FFFFFF));
        }
        // 发光边缘
        for (int i = 0; i < 5; i++) {
            int inset = i;
            int a = (int) ((70 - i * 12) * alpha);
            if (a <= 0) {
                continue;
            }
            int color = (a << 24) | 0x9FEBFF;
            int baseY = y + panelH - 1 - inset;
            g.fill(x + panelW / 2 - (int) (panelW * 0.45F) + inset, baseY,
                    x + panelW / 2 + (int) (panelW * 0.45F) - inset, baseY + 1, color);
        }

        int centerX = x + panelW / 2;
        // 彩虹标题（外描边 + 主体）
        drawRainbowText(g, minecraft, "BLOOM SYSTEM", centerX, y + 10, 1.25F, now, alpha);
        drawRainbowText(g, minecraft, "棱镜法阵 · PRISM RITUAL", centerX, y + 26, 0.88F, now + 400, alpha);

        // 阶段 / 进度条
        String phase = switch (timeline.phaseName()) {
            case "deploy" -> "阶段 1/5 · 法阵展开";
            case "charge" -> "阶段 2/5 · 充能";
            case "burst" -> "阶段 3/5 · 棱镜爆发";
            case "sustain" -> "阶段 4/5 · 维持";
            case "fade" -> "阶段 5/5 · 淡出";
            default -> "待机";
        };
        int textColor = ((int) (alpha * 235) << 24) | 0xE8D8FF;
        g.centeredText(minecraft.font, Component.literal(phase), centerX, y + 47, textColor);

        int barW = (int) (panelW * 0.7F);
        int barX = centerX - barW / 2;
        int barY = y + 57;
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, ((int) (alpha * 180) << 24) | 0x1A0B33);
        int filled = (int) (barW * timeline.progress());
        for (int i = 0; i < filled; i++) {
            float hue = ((now * 0.0004F) + i * 0.012F) % 1.0F;
            int rgb = Color.HSBtoRGB(hue, 0.75F, 1.0F) & 0x00FFFFFF;
            g.fill(barX + i, barY, barX + i + 1, barY + 6, ((int) (alpha * 230) << 24) | rgb);
        }

        // 边缘星光
        int sparkleSeed = (int) (now / 90L);
        Random random = new Random(sparkleSeed);
        for (int i = 0; i < 18; i++) {
            int sx = x + 6 + random.nextInt(panelW - 12);
            int sy = y + 8 + random.nextInt(panelH - 14);
            float twinkle = 0.4F + 0.6F * random.nextFloat();
            int a = (int) (alpha * twinkle * 200);
            int size = random.nextBoolean() ? 1 : 2;
            g.fill(sx, sy, sx + size, sy + size, (a << 24) | 0xFFFFFF);
        }
    }

    private static void drawRainbowText(GuiGraphicsExtractor g, Minecraft minecraft,
                                        String text, int centerX, int y, float scale, long time, float alpha) {
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(centerX, y);
        pose.scale(scale, scale);
        int total = minecraft.font.width(Component.literal(text));
        int penX = -total / 2;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int width = minecraft.font.width(Component.literal(ch));
            float hue = ((time * 0.00035F) + i * 0.045F) % 1.0F;
            int rgb = Color.HSBtoRGB(hue, 0.85F, 1.0F) & 0x00FFFFFF;
            int glowAlpha = (int) (alpha * 90);
            int glowColor = (glowAlpha << 24) | rgb;
            int mainColor = ((int) (alpha * 255) << 24) | rgb;
            g.text(minecraft.font, Component.literal(ch), penX - 1, -1, glowColor);
            g.text(minecraft.font, Component.literal(ch), penX + 1, 1, glowColor);
            g.text(minecraft.font, Component.literal(ch), penX, 0, mainColor);
            penX += width;
        }
        pose.popMatrix();
    }

    private static int lerpColor(int from, int to, float t) {
        int a = (int) (((from >>> 24) & 0xFF) * (1 - t) + ((to >>> 24) & 0xFF) * t);
        int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
