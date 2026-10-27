package com.vjj.client.ui;

import com.vjj.client.fx.ArcaneToastHud;
import com.vjj.client.fx.FxManager;
import com.vjj.fx.FxType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 奥术特效工坊（FX Lab）：模仿 ArcaneVortex 的 UI 语言（深紫星云背景、发光面板、符文装饰、
 * 渐变按钮），让管理员预览/调参 VJJ-NEW 特效库。
 *
 * <p>不依赖服务端：纯客户端预览；服务端特效由 {@code /vjj fx ...} 广播。
 * 打开方式：{@code /vjjc fxlab}、{@code /vjj panel fx}（OP）。</p>
 *
 * <p>布局按 26.3 默认 GUI 缩放（常见 427×240 / 640×360 逻辑像素）自适应：左侧单列效果列表，
 * 右侧详情/调参面板。</p>
 */
public final class FxLabScreen extends Screen {
    private static final int ACCENT = ArcaneUi.ACCENT;
    private static final int GOLD = ArcaneUi.GOLD;
    private static final int ROW_H = 17;

    private final List<Btn> buttons = new ArrayList<>();
    private FxType selected = FxType.RING;
    private int color = FxType.RING.defaultColor;
    private float scale = 1.0F;
    private int duration;

    public FxLabScreen() {
        super(Component.literal("奥术特效工坊"));
        this.duration = FxType.RING.defaultDurationTicks;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        buttons.clear();
        long time = System.currentTimeMillis();
        ArcaneUi.background(graphics, time);

        int panelX = 10;
        int panelY = 28;
        int panelW = width - 20;
        int panelH = height - 36;
        int panelRight = panelX + panelW;
        int panelBottom = panelY + panelH;
        ArcaneUi.panel(graphics, panelX, panelY, panelW, panelH, ACCENT);

        ArcaneUi.titleScaled(graphics, this.font, "VJJ-NEW · 奥术特效工坊", width / 2, 2, 1.5F, GOLD);
        graphics.centeredText(this.font, Component.literal("ARCANE VORTEX STYLE · EFFECTS & UI LIBRARY"),
                width / 2, 20, ArcaneUi.TEXT_DIM);

        // 左：效果列表（单列，适配小分辨率）
        int listX = panelX + 8;
        int listY = panelY + 10;
        int listW = Math.min(154, Math.max(120, panelW * 38 / 100));
        FxType[] types = java.util.Arrays.stream(FxType.values())
                .filter(type -> type != FxType.CIRCLE).toArray(FxType[]::new);
        for (int i = 0; i < types.length; i++) {
            FxType type = types[i];
            int by = listY + i * ROW_H;
            if (by + 15 > panelBottom - 12) {
                break;
            }
            boolean isSelected = type == selected;
            boolean hover = inside(mouseX, mouseY, listX, by, listW, 15);
            ArcaneUi.button(graphics, this.font, listX, by, listW, 15,
                    "· " + type.displayName, isSelected, hover, type.defaultColor);
            ArcaneUi.icon(graphics, listX + 2, by + 1, 13, type.defaultColor);
            buttons.add(new Btn("type:" + type.id, listX, by, listW, 15));
        }

        // 右：详情 + 调参
        int detailX = listX + listW + 10;
        int detailW = panelRight - 10 - detailX;
        int detailY = panelY + 10;
        ArcaneUi.divider(graphics, detailX, panelRight - 10, detailY - 2, ACCENT);
        String name = selected.displayName + "  ·  " + selected.id;
        String clipped = this.font.plainSubstrByWidth(name, Math.max(20, detailW - 8));
        graphics.centeredText(this.font, Component.literal(clipped), detailX + detailW / 2, detailY + 2,
                GOLD);

        ArcaneUi.rune(graphics, detailX + detailW / 2, detailY + 38, 42, time / 30.0F, 0.9F);
        String desc = description(selected);
        String desc1 = this.font.plainSubstrByWidth(desc, Math.max(20, detailW - 6));
        graphics.text(this.font, Component.literal(desc1), detailX, detailY + 62, ArcaneUi.TEXT);
        String desc2 = this.font.plainSubstrByWidth("默认时长 " + selected.defaultDurationTicks + "t · 当前 "
                + duration + "t", Math.max(20, detailW - 6));
        graphics.text(this.font, Component.literal(desc2), detailX, detailY + 74, ArcaneUi.TEXT_DIM);

        // 颜色
        int colorY = detailY + 92;
        graphics.text(this.font, Component.literal("颜色"), detailX, colorY, ArcaneUi.TEXT_DIM);
        int[] palette = {0xFFFFD060, 0xFF66E0FF, 0xFFB07CFF, 0xFFFF6B8B, 0xFF7CFC98, 0xFFFFFFFF};
        for (int i = 0; i < palette.length; i++) {
            int sx = detailX + 26 + i * 19;
            ArcaneUi.swatch(graphics, sx, colorY - 1, 15, 10, palette[i], color == palette[i]);
            buttons.add(new Btn("color:" + palette[i], sx, colorY - 1, 15, 10));
        }

        // 缩放
        int scaleY = colorY + 18;
        graphics.text(this.font, Component.literal(String.format(Locale.ROOT, "缩放 %.2fx", scale)),
                detailX, scaleY, ArcaneUi.TEXT);
        ArcaneUi.button(graphics, this.font, detailX + 70, scaleY - 3, 20, 14, "-", false,
                inside(mouseX, mouseY, detailX + 70, scaleY - 3, 20, 14), ACCENT);
        ArcaneUi.button(graphics, this.font, detailX + 92, scaleY - 3, 20, 14, "+", false,
                inside(mouseX, mouseY, detailX + 92, scaleY - 3, 20, 14), ACCENT);
        buttons.add(new Btn("scale:-", detailX + 70, scaleY - 3, 20, 14));
        buttons.add(new Btn("scale:+", detailX + 92, scaleY - 3, 20, 14));

        // 时长
        int durY = scaleY + 18;
        graphics.text(this.font, Component.literal("时长 " + duration + "t"), detailX, durY, ArcaneUi.TEXT);
        ArcaneUi.button(graphics, this.font, detailX + 70, durY - 3, 20, 14, "-", false,
                inside(mouseX, mouseY, detailX + 70, durY - 3, 20, 14), ACCENT);
        ArcaneUi.button(graphics, this.font, detailX + 92, durY - 3, 20, 14, "+", false,
                inside(mouseX, mouseY, detailX + 92, durY - 3, 20, 14), ACCENT);
        buttons.add(new Btn("duration:-", detailX + 70, durY - 3, 20, 14));
        buttons.add(new Btn("duration:+", detailX + 92, durY - 3, 20, 14));

        // 预览 / 关闭
        int actionY = durY + 20;
        int previewW = Math.min(150, Math.max(90, detailW * 62 / 100));
        ArcaneUi.button(graphics, this.font, detailX, actionY, previewW, 20, "▶ 预览特效", true,
                inside(mouseX, mouseY, detailX, actionY, previewW, 20), GOLD);
        int closeX = detailX + previewW + 8;
        ArcaneUi.button(graphics, this.font, closeX, actionY, 70, 20, "关闭", false,
                inside(mouseX, mouseY, closeX, actionY, 70, 20), ACCENT);
        buttons.add(new Btn("preview", detailX, actionY, previewW, 20));
        buttons.add(new Btn("close", closeX, actionY, 70, 20));

        String hint = this.font.plainSubstrByWidth(
                "本界面只做本地预览；服务器广播用 /vjj fx <类型>", Math.max(20, panelW - 20));
        graphics.text(this.font, Component.literal(hint), panelX + 10, panelBottom - 12, ArcaneUi.TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 1) {
            return super.mouseClicked(event, doubleClick);
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        for (Btn btn : buttons) {
            if (inside(mouseX, mouseY, btn.x, btn.y, btn.w, btn.h)) {
                onButton(btn.id);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void onButton(String id) {
        if (id.startsWith("type:")) {
            FxType type = FxType.byId(id.substring(5));
            if (type != null) {
                selected = type;
                color = type.defaultColor;
                duration = type.defaultDurationTicks;
            }
            return;
        }
        if (id.startsWith("color:")) {
            color = Integer.parseInt(id.substring(6));
            return;
        }
        switch (id) {
            case "scale:-" -> scale = Math.max(0.4F, Math.round((scale - 0.1F) * 10.0F) / 10.0F);
            case "scale:+" -> scale = Math.min(4.0F, Math.round((scale + 0.1F) * 10.0F) / 10.0F);
            case "duration:-" -> duration = Math.max(10, duration - 10);
            case "duration:+" -> duration = Math.min(400, duration + 10);
            case "preview" -> {
                FxManager.preview(selected, color, scale, duration);
                ArcaneToastHud.push(selected);
            }
            case "close" -> onClose();
            default -> {
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && this.shouldCloseOnEsc()) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String description(FxType type) {
        return switch (type) {
            case RING -> "地面扩散的奥术光环，适合技能落地/传送门。";
            case VORTEX -> "螺旋上升的星涡，适合蓄力/召唤法阵。";
            case BEAM -> "垂直能量光柱，适合天降/审判/充能。";
            case SHOCKWAVE -> "球面冲击波，适合爆裂/重击命中。";
            case AURA -> "环绕施法者的灵气，适合持续增益。";
            case RUNE -> "地面旋转符文阵，适合咏唱/召唤。";
            case FLASH -> "全屏闪光 + 爆点，适合暴击/处决。";
            case COSMIC -> "全屏星云星空，适合大招/过场。";
            case BURST -> "瞬时放射尘光与电火花，适合爆炸。";
            case PRISM -> "彩色棱光电弧环绕，适合护盾/反射。";
            case CIRCLE -> "大型地面奥术法阵（服务端 /vjj fx circle 生成）。";
        };
    }

    private record Btn(String id, int x, int y, int w, int h) {
    }
}
