package com.vjj.client.ui;

import com.vjj.client.VjjUiClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * VJJ-NEW 可视化界面的公共骨架（26.3 Screen API：绘制在 {@code extractRenderState}，
 * 输入是 {@code MouseButtonEvent}/{@code KeyEvent}；没有 {@code render(GuiGraphics,...)}）。
 *
 * <p>所有界面共用同一套自绘按钮：{@link #button(String, String, int, int, int, int)} 注册，
 * 点击命中后回调 {@link #onButton(String)}；{@link #testClick(String)} / {@link #testButtonIds()}
 * 给客户端 autotest 用（E2E 通过它点击并截图，不需要真鼠标）。</p>
 */
public abstract class VjjScreenBase extends Screen {
    public static final int PANEL_BG = 0xF00A0C12;
    public static final int PANEL_BORDER = 0xFF3C465C;
    public static final int COLOR_TITLE = 0xFFFFD060;
    public static final int COLOR_TEXT = 0xFFEDEDED;
    public static final int COLOR_DIM = 0xFF909090;
    public static final int COLOR_OK = 0xFF7CFC98;
    public static final int BUTTON_BG = 0xD81E3A5F;
    public static final int BUTTON_HOVER = 0xFF2E5A8F;
    public static final int BUTTON_ACTIVE = 0xFF2E7A3F;
    public static final int BUTTON_DANGER = 0xD86A2020;
    public static final int BUTTON_DISABLED = 0xFF2A2A2A;

    /** 一个自绘按钮。 */
    public record Btn(String id, String label, int x, int y, int w, int h) {
    }

    protected final List<Btn> buttons = new ArrayList<>();
    /** 只用于视觉高亮的选中态按钮 id（不影响点击逻辑）。 */
    protected String activeButtonId = "";

    protected VjjScreenBase(Component title) {
        super(title);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 布局 / 绘制
    // ─────────────────────────────────────────────────────────────────────

    protected final void clearButtons() {
        this.buttons.clear();
    }

    protected final Btn button(String id, String label, int x, int y, int w, int h) {
        Btn btn = new Btn(id, label, x, y, w, h);
        this.buttons.add(btn);
        return btn;
    }

    /** 屏幕顶部标题条 + 状态行（子类按需调用）。 */
    protected final void drawHeader(GuiGraphicsExtractor graphics, String subtitle) {
        graphics.fill(0, 0, this.width, this.height, PANEL_BG);
        graphics.fill(0, 0, this.width, 26, 0xFF121826);
        graphics.outline(0, 0, this.width, 26, PANEL_BORDER);
        graphics.text(this.font, this.getTitle(), 10, 8, COLOR_TITLE);
        if (subtitle != null && !subtitle.isEmpty()) {
            int width = this.font.width(Component.literal(subtitle));
            graphics.text(this.font, Component.literal(subtitle), this.width - 10 - width, 8, COLOR_DIM);
        }
    }

    protected final void drawButtonRect(GuiGraphicsExtractor graphics, Btn btn, int mouseX, int mouseY,
                                        boolean highlighted) {
        boolean hover = inside(mouseX, mouseY, btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h());
        int background;
        if (highlighted) {
            background = BUTTON_ACTIVE;
        } else if (btn.id().startsWith("danger:")) {
            background = hover ? 0xFF903030 : BUTTON_DANGER;
        } else if (btn.id().startsWith("disabled:")) {
            background = BUTTON_DISABLED;
        } else {
            background = hover ? BUTTON_HOVER : BUTTON_BG;
        }
        graphics.fill(btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h(), background);
        graphics.outline(btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h(), PANEL_BORDER);
        String label = btn.label() == null ? "" : btn.label();
        label = this.font.plainSubstrByWidth(label, Math.max(8, btn.w() - 8));
        graphics.centeredText(this.font, Component.literal(label),
                (btn.x() * 2 + btn.w()) / 2, btn.y() + (btn.h() - 8) / 2 + 1,
                btn.id().startsWith("disabled:") ? COLOR_DIM : COLOR_TEXT);
    }

    protected final void drawButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (Btn btn : this.buttons) {
            drawButtonRect(graphics, btn, mouseX, mouseY, btn.id().equals(this.activeButtonId));
        }
    }

    /** 面板（半透明底 + 边框）。 */
    protected final void panel(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y2, 0xE0101218);
        graphics.outline(x1, y1, x2, y2, PANEL_BORDER);
    }

    protected final void label(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.text(this.font, Component.literal(text == null ? "" : text), x, y, color);
    }

    protected final void labelCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
        graphics.centeredText(this.font, Component.literal(text == null ? "" : text), centerX, y, color);
    }

    protected final void hint(GuiGraphicsExtractor graphics, String text) {
        label(graphics, text, 10, this.height - 14, COLOR_DIM);
    }

    /** 子类绘制内容（按钮绘制由基类在之后统一做，保证按钮在内容之上）。 */
    protected abstract void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        renderContent(graphics, mouseX, mouseY);
        drawButtons(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 输入
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        // 26.3 用 SDL 键值：鼠标左键 = 1（旧 GLFW 的 0 会被静默丢弃，见 npcmod 的坑）
        if (event.button() != 1) {
            return false;
        }
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        for (Btn btn : this.buttons) {
            if (inside(mouseX, mouseY, btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h())) {
                boolean handled = onButton(btn.id());
                if (handled) {
                    VjjUiClient.sendUiEvent("click", btn.id());
                }
                return handled;
            }
        }
        return false;
    }

    /** 按钮回调；返回 true 表示已处理（autotest 也走这里）。 */
    protected boolean onButton(String id) {
        return false;
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

    // ─────────────────────────────────────────────────────────────────────
    // autotest 入口
    // ─────────────────────────────────────────────────────────────────────

    /** 模拟点击某个按钮（E2E autotest 用）。 */
    public final boolean testClick(String buttonId) {
        for (Btn btn : this.buttons) {
            if (btn.id().equals(buttonId)) {
                boolean handled = onButton(buttonId);
                if (handled) {
                    VjjUiClient.LOGGER.info("[VjjNew] ui-test-click screen={} button={} handled=true",
                            this.getClass().getSimpleName(), buttonId);
                }
                return handled;
            }
        }
        VjjUiClient.LOGGER.info("[VjjNew] ui-test-click screen={} button={} handled=false reason=missing",
                this.getClass().getSimpleName(), buttonId);
        return false;
    }

    /** 当前界面全部按钮 id（autotest 发现可点项）。 */
    public final List<String> testButtonIds() {
        List<String> ids = new ArrayList<>();
        for (Btn btn : this.buttons) {
            ids.add(btn.id());
        }
        return ids;
    }

    protected static boolean inside(int mouseX, int mouseY, int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }
}
