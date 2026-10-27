package com.vjj.client.subtitle;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * UI 字幕 HUD：把服务端 {@code /subtitle ui ...} 发来的台词画在玩家屏幕上。
 *
 * <p>与聊天栏/动作 HUD 不同，这里是**纯文字**（可选阴影），没有黑框背景板；
 * 每句台词有独立的屏幕比例位置（x/y 0..1，由管理员逐句指定），因此同一时间多句可以出现在不同位置。
 * 时长/淡入淡出由本类按真实时间计算（不受暂停影响），0 时长 = 常驻直到 {@code /subtitle clearui}。</p>
 */
public final class VjjSubtitleHud {
    private static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("vjjnew", "subtitle_hud");

    private static final long FADE_MS = 220L;
    private static final int MAX_LINES = 32;
    private static final List<Line> LINES = new ArrayList<>();

    private VjjSubtitleHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, VjjSubtitleHud::render);
        LOGGER.info("[VjjNew] subtitle hud registered id={}", ELEMENT_ID);
    }

    /** 客户端收到一句话幕：未指定时长（0）= 常驻。 */
    public static void show(String text, float xFrac, float yFrac, int durationTicks,
                            int color, float scale, boolean shadow) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (LINES.size() >= MAX_LINES) {
            LINES.remove(0);
        }
        Line line = new Line();
        line.text = text;
        line.xFrac = Math.max(0.0F, Math.min(1.0F, xFrac));
        line.yFrac = Math.max(0.0F, Math.min(1.0F, yFrac));
        line.startMs = System.currentTimeMillis();
        line.durationMs = durationTicks <= 0 ? 0L : (long) durationTicks * 50L;
        line.color = color;
        line.scale = Math.max(0.25F, Math.min(4.0F, scale));
        line.shadow = shadow;
        LINES.add(line);
        LOGGER.info("[VjjNew] subtitle-ui-receive x={} y={} duration={}t color={} scale={} text=\"{}\"",
                line.xFrac, line.yFrac, durationTicks, Integer.toHexString(color), line.scale, text);
    }

    public static void clear() {
        if (!LINES.isEmpty()) {
            LOGGER.info("[VjjNew] subtitle-ui-clear count={}", LINES.size());
        }
        LINES.clear();
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (LINES.isEmpty()) {
            return;
        }
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.font == null || minecraft.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        Iterator<Line> iterator = LINES.iterator();
        while (iterator.hasNext()) {
            Line line = iterator.next();
            long elapsed = now - line.startMs;
            if (line.durationMs > 0L && elapsed >= line.durationMs) {
                iterator.remove();
                continue;
            }
            float alpha = 1.0F;
            if (elapsed < FADE_MS) {
                alpha = elapsed / (float) FADE_MS;
            } else if (line.durationMs > 0L) {
                long remaining = line.durationMs - elapsed;
                if (remaining < FADE_MS) {
                    alpha = remaining / (float) FADE_MS;
                }
            }
            alpha = Math.max(0.0F, Math.min(1.0F, alpha));
            int argb = ((int) (alpha * 255.0F) << 24) | (line.color & 0x00FFFFFF);
            int width = minecraft.font.width(line.text);
            int centerX = Math.round(guiWidth * line.xFrac);
            int centerY = Math.round(guiHeight * line.yFrac);
            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(centerX, centerY);
            pose.scale(line.scale, line.scale);
            graphics.text(minecraft.font, line.text, -width / 2, -4, argb, line.shadow);
            pose.popMatrix();
        }
    }

    private static final class Line {
        String text;
        float xFrac;
        float yFrac;
        long startMs;
        long durationMs;
        int color;
        float scale;
        boolean shadow;
    }
}
