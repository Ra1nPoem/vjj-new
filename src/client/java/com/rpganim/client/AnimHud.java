package com.rpganim.client;

import com.rpganim.anim.AnimDefinition;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 左上角动作 HUD：本地玩家动作播放期间显示「动作名 [分类] + t/duration + 进度条」，
 * 过场期间在动作信息上方显示过场标题 + 剩余进度条；没有动作/过场时不画任何东西。
 * <p>26.3 的 HUD 注册 API 是 fabric-rendering-v1 的 {@code HudElementRegistry}（{@code HudRenderCallback} 已删除），
 * 绘制用 {@code GuiGraphicsExtractor#fill/outline/text}（参考 questguide 的 QuestHud）。
 * <p>位置选左上角而不是左下角：左下角被聊天栏占用，HUD 会被聊天文字叠住。
 */
public final class AnimHud {
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath("rpganim", "anim_hud");

    private static final int MARGIN = 8;

    private static final int BG = 0xC0000000;
    private static final int BORDER = 0x60FFFFFF;
    private static final int COLOR_TITLE = 0xFFFFD060;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_CUTSCENE = 0xFFFF9E40;
    private static final int BAR_BG = 0xFF303030;
    private static final int BAR_FG = 0xFF55CDFF;
    private static final int BAR_CUTSCENE = 0xFFFF9E40;

    private AnimHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, AnimHud::render);
        RpgAnimClient.LOGGER.info("[RpgAnim] hud element registered {}", ELEMENT_ID);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ClientAnimState.ActiveCutscene cutscene = ClientAnimState.currentCutscene();
        ClientAnimState.ActiveAnim action = ClientAnimState.active(mc.player.getId());
        if (cutscene == null && action == null) {
            return;
        }
        int width = Math.max(148, Math.min(220, (int) (g.guiWidth() * 0.38)));
        int height = 8 + (cutscene != null ? 18 : 0) + (action != null ? 28 : 0);
        int x = MARGIN;
        // 左上角：避免和左下角聊天栏重叠（HUD 面板底色提高不透明度，叠在场景上也清晰）
        int y = MARGIN;
        panel(g, x, y, x + width, y + height);

        int cursorY = y + 4;
        if (cutscene != null) {
            g.text(mc.font, clip(mc, cutsceneTitle(cutscene), width - 12), x + 6, cursorY, COLOR_CUTSCENE);
            cursorY += 10;
            bar(g, x + 6, cursorY, width - 12, 4, ClientAnimState.cutsceneProgress(cutscene), BAR_CUTSCENE);
            cursorY += 8;
        }
        if (action != null) {
            g.text(mc.font, clip(mc, actionTitle(action), width - 12), x + 6, cursorY, COLOR_TITLE);
            cursorY += 10;
            g.text(mc.font, clip(mc, actionDetail(action), width - 12), x + 6, cursorY, COLOR_DIM);
            cursorY += 10;
            bar(g, x + 6, cursorY, width - 12, 4, ClientAnimState.progress(action), BAR_FG);
        }
    }

    private static Component cutsceneTitle(ClientAnimState.ActiveCutscene cutscene) {
        Component title = Component.translatableWithFallback(cutscene.titleKey, cutscene.cutsceneId);
        return Component.translatableWithFallback("hud.rpganim.cutscene", "Cutscene: %s", title);
    }

    private static Component actionTitle(ClientAnimState.ActiveAnim action) {
        return Component.translatableWithFallback("hud.rpganim.action", "Action: %s", actionName(action));
    }

    private static Component actionName(ClientAnimState.ActiveAnim action) {
        AnimDefinition definition = action.def;
        if (definition != null && definition.nameKey() != null && !definition.nameKey().isBlank()) {
            return Component.translatable(definition.nameKey());
        }
        return Component.literal(action.animId);
    }

    /** 第二行：分类 + 已播 tick/总时长 + 百分比。 */
    private static Component actionDetail(ClientAnimState.ActiveAnim action) {
        AnimDefinition definition = action.def;
        String category = definition == null || definition.category() == null ? "" : definition.category();
        String categoryText = category.isBlank()
                ? ""
                : Component.translatableWithFallback("category.rpganim." + category, category).getString();
        int ticks = Math.min(action.ageTicks, action.durationTicks);
        int percent = Math.round(ClientAnimState.progress(action) * 100.0F);
        String percentText = Component.translatableWithFallback("hud.rpganim.progress", "%s%%", percent).getString();
        String detail = categoryText.isBlank()
                ? ticks + "/" + action.durationTicks + "t  " + percentText
                : categoryText + "  " + ticks + "/" + action.durationTicks + "t  " + percentText;
        return Component.literal(detail);
    }

    /** 按像素宽度截断组件文本（小分辨率下避免 HUD 文字溢出面板）。 */
    private static String clip(Minecraft mc, Component component, int maxWidth) {
        return mc.font.plainSubstrByWidth(component.getString(), Math.max(8, maxWidth));
    }

    private static void panel(GuiGraphicsExtractor g, int left, int top, int right, int bottom) {
        g.fill(left, top, right, bottom, BG);
        g.outline(left, top, right, bottom, BORDER);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int width, int height, float ratio, int color) {
        g.fill(x, y, x + width, y + height, BAR_BG);
        int filled = (int) (width * Math.max(0.0F, Math.min(1.0F, ratio)));
        if (filled > 0) {
            g.fill(x, y, x + filled, y + height, color);
        }
    }
}
