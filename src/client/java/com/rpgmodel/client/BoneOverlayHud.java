package com.rpgmodel.client;

import com.rpgmodel.RpgModel;
import com.rpgmodel.api.Rig;
import com.rpgmodel.api.RpgModelApi;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * 骨骼调试覆盖层（HUD，屏幕左上角）：
 * 显示 rig 名、绑定到的骨骼数、本地玩家每根骨骼的 xRot/yRot/zRot（弧度，模型被动作驱动时会实时变化）。
 *
 * <p>用 fabric-rendering-v1 的 {@link HudElementRegistry} 注册（26.3 没有 HudRenderCallback）；
 * 绘制走 {@link GuiGraphicsExtractor} 的 fill/outline/text。</p>
 */
public final class BoneOverlayHud {
    public static final Identifier ELEMENT_ID = Identifier.fromNamespaceAndPath(RpgModel.MOD_ID, "bone_overlay");

    private static final int BACKGROUND = 0xB4000000;
    private static final int BORDER = 0x60FFFFFF;
    private static final int COLOR_TITLE = 0xFFFFD060;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_VALUE = 0xFF55FF55;
    private static final int COLOR_FOOTER = 0xFF808080;

    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;

    private BoneOverlayHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, BoneOverlayHud::render);
        RpgModel.LOGGER.info("[RpgModel] hud element registered {}", ELEMENT_ID);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (!OverlayState.enabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        Rig rig = RpgModelApi.getRig(PoseCapture.RIG_ID);
        PoseCapture.Snapshot snapshot = PoseCapture.snapshot();

        int boneCount = snapshot == null ? 0 : snapshot.bones().size();
        int panelHeight = (2 + Math.max(1, boneCount)) * LINE_HEIGHT + PADDING * 3 + LINE_HEIGHT * 2;
        int x = 6;
        int y = 6;
        int width = 236;
        int right = Math.min(x + width, graphics.guiWidth() - 6);
        int bottom = Math.min(y + panelHeight, graphics.guiHeight() - 6);

        graphics.fill(x, y, right, bottom, BACKGROUND);
        graphics.outline(x, y, right, bottom, BORDER);

        int textX = x + PADDING;
        int cursorY = y + PADDING;
        graphics.text(client.font, Component.translatable("overlay.rpgmodel.title"), textX, cursorY, COLOR_TITLE);
        cursorY += LINE_HEIGHT;

        String rigName = rig == null ? PoseCapture.RIG_ID + "(missing)" : rig.id();
        int expected = rig == null ? 0 : rig.boneNames().size();
        int bound = snapshot == null ? 0 : snapshot.bound();
        int missing = snapshot == null ? 0 : snapshot.missing();
        graphics.text(client.font,
                Component.translatable("overlay.rpgmodel.binding", rigName, bound, expected, missing),
                textX, cursorY, COLOR_TEXT);
        cursorY += LINE_HEIGHT;

        if (snapshot == null) {
            graphics.text(client.font, Component.translatable("overlay.rpgmodel.waiting"), textX, cursorY, COLOR_DIM);
        } else {
            for (PoseCapture.BonePose bone : snapshot.bones()) {
                if (cursorY + LINE_HEIGHT > bottom - LINE_HEIGHT) {
                    break;
                }
                String line = String.format(Locale.ROOT, "%s rot=%.3f,%.3f,%.3f",
                        bone.name(), bone.xRot(), bone.yRot(), bone.zRot());
                graphics.text(client.font, line, textX, cursorY, COLOR_VALUE);
                cursorY += LINE_HEIGHT;
            }
        }

        if (cursorY + LINE_HEIGHT <= bottom) {
            graphics.text(client.font, Component.translatable("overlay.rpgmodel.hint"), textX, cursorY + 2, COLOR_FOOTER);
        }
    }
}
