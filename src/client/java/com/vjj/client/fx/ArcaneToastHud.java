package com.vjj.client.fx;

import com.vjj.client.ui.ArcaneUi;
import com.vjj.fx.FxType;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 奥术特效触发提示（HUD 吐司）：屏幕上方居中的「符文 + 效果名」打字机动画，
 * 视觉语言与 ArcaneVortex 的 UI 通知/对话一致（深紫面板、发光边框、符文装饰）。
 */
public final class ArcaneToastHud {
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("vjjnew", "fx_toast");
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static final long DURATION_MS = 2400L;

    private ArcaneToastHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, ArcaneToastHud::render);
    }

    public static void push(FxType type) {
        if (type == null) {
            return;
        }
        if (TOASTS.size() >= 3) {
            TOASTS.remove(0);
        }
        TOASTS.add(new Toast(type.displayName, type.defaultColor, System.currentTimeMillis()));
    }

    public static void clear() {
        TOASTS.clear();
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (TOASTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null || minecraft.level == null) {
            return;
        }
        int w = graphics.guiWidth();
        long now = System.currentTimeMillis();
        int y = 44;
        Iterator<Toast> iterator = TOASTS.iterator();
        while (iterator.hasNext()) {
            Toast toast = iterator.next();
            long elapsed = now - toast.startMs;
            if (elapsed >= DURATION_MS) {
                iterator.remove();
                continue;
            }
            float fade = elapsed < 200L ? elapsed / 200.0F
                    : elapsed > DURATION_MS - 350L ? (DURATION_MS - elapsed) / 350.0F : 1.0F;
            fade = Math.max(0.0F, Math.min(1.0F, fade));
            String text = toast.text;
            int chars = (int) Math.min(text.length(), elapsed / 28L);
            String shown = text.substring(0, chars);
            int boxW = Math.max(180, minecraft.font.width(Component.literal(text)) + 54);
            int x = w / 2 - boxW / 2;
            ArcaneUi.panel(graphics, x, y, boxW, 24, toast.color);
            ArcaneUi.icon(graphics, x + 7, y + 4, 16, toast.color);
            graphics.text(minecraft.font, Component.literal("ARCANE · " + shown), x + 28, y + 8,
                    (int) (fade * 255) << 24 | (0xE8D8FF));
            y += 28;
        }
    }

    private static final class Toast {
        final String text;
        final int color;
        final long startMs;

        Toast(String text, int color, long startMs) {
            this.text = text;
            this.color = color;
            this.startMs = startMs;
        }
    }
}
