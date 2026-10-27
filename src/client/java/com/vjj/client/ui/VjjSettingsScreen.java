package com.vjj.client.ui;

import com.vjj.client.VjjUiClient;
import com.vjj.client.state.VjjEditorState;
import com.vjj.compat.EmfBridge;
import com.vjj.compat.PunchyBridge;
import com.vjj.compat.VjjCompat;
import com.vjj.store.VjjSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 设置与保存页：OP 打开/关闭演出默认行为（强制镜头、锁死行动、隐藏玩家、假人替代、镜头广播、变焦），
 * 以及 1.1.0 的**玩家动画优化**开关（EMF 动画管线兼容、VJJ 姿势优先级、外部叠加权重）；
 * 所有开关动作由服务端处理并回快照，界面下一帧刷新。
 *
 * <p>本页还会显示客户端检测到的动画生态：EMF / ETF / Touch'n Motion / EMF Compat Core /
 * Fresh Animations: Player Extension，以及当前动作走的是 EMF late-pose 管线还是原生 mixin。</p>
 *
 * <p>布局按 26.3 默认 GUI 缩放（640×360 逻辑像素）设计：左列 8 个演出开关 + 4 个动画优化开关，
 * 右列 4 个操作按钮；底部两行显示兼容检测与提示（1.1.0 曾因 16 按钮在 360 高度下重叠，这里做了紧凑布局）。</p>
 */
public final class VjjSettingsScreen extends VjjScreenBase {
    /** 按钮高度/行距（紧凑，保证 640×360 下不重叠、不裁切）。 */
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 3;

    public VjjSettingsScreen() {
        super(Component.translatable("vjj.ui.settings.title"));
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        VjjEditorState state = VjjEditorState.get();
        VjjSettings settings = state.settings();
        drawHeader(graphics, "session=" + state.sessionId());

        int left = 16;
        int top = 30;
        int right = this.width - 16;
        int bottom = this.height - 8;
        panel(graphics, left, top, right, bottom);
        label(graphics, "演出默认行为（服务端权威）", left + 10, top + 8, COLOR_TITLE);
        label(graphics, "状态：" + state.status(), left + 10, top + 21, COLOR_OK);

        clearButtons();
        int x = left + 10;
        int leftWidth = Math.min(296, (int) (this.width * 0.47));
        int w = leftWidth;
        int rowGap = ROW_H + ROW_GAP;
        int y = top + 36;
        button("settings:force", "强制玩家镜头：" + onOff(settings.forceCamera), x, y, w, ROW_H);
        button("settings:lock", "锁死玩家行动：" + onOff(settings.lockAction), x, y + rowGap, w, ROW_H);
        button("settings:hide", "隐藏玩家本体：" + onOff(settings.hidePlayer), x, y + 2 * rowGap, w, ROW_H);
        button("settings:dummy", "播放动画时用假人替代：" + onOff(settings.spawnDummy), x, y + 3 * rowGap, w, ROW_H);
        button("settings:broadcast", "镜头广播给所有玩家：" + onOff(settings.broadcastCamera), x, y + 4 * rowGap, w, ROW_H);
        button("settings:zoom_enabled", "客户端变焦开关：" + onOff(settings.zoomEnabled), x, y + 5 * rowGap, w, ROW_H);
        button("settings:zoom_down", "变焦倍率 - （当前 " + fmt(settings.zoomFactor) + "x）", x, y + 6 * rowGap, w, ROW_H);
        button("settings:zoom_up", "变焦倍率 + （当前 " + fmt(settings.zoomFactor) + "x）", x, y + 7 * rowGap, w, ROW_H);

        // 1.1.0 玩家动画优化（外部动画生态）——两列小按钮，避免在 640×360 下溢出
        int animY = y + 8 * rowGap + 4;
        label(graphics, "玩家动画优化（EMF / Touch'n Motion / FA）", x, animY, COLOR_TITLE);
        int half = (w - 6) / 2;
        int rowA = animY + 14;
        int rowB = rowA + ROW_H + ROW_GAP;
        button("settings:emf_bridge", "EMF 管线：" + onOff(settings.emfBridge), x, rowA, half, ROW_H);
        button("settings:pose_priority", "VJJ 优先：" + onOff(settings.externalPosePriority),
                x + half + 6, rowA, half, ROW_H);
        button("settings:weight_down", "权重 - " + fmt(settings.overlayWeight), x, rowB, half, ROW_H);
        button("settings:weight_up", "权重 + " + fmt(settings.overlayWeight), x + half + 6, rowB, half, ROW_H);

        // 右列：保存/重载/返回/关闭
        int x2 = x + leftWidth + 12;
        int w2 = Math.max(140, right - 10 - x2);
        int by = top + 36;
        button("settings:save", "保存配置", x2, by, w2, 20);
        button("settings:reload", "重新载入", x2, by + 26, w2, 20);
        button("settings:back", "返回主页", x2, by + 52, w2, 20);
        button("settings:close", "关闭界面", x2, by + 78, w2, 20);

        // 底部：客户端动画生态检测 + 当前管线（只读）
        int infoX = x;
        int infoY = rowB + ROW_H + 6;
        label(graphics, VjjCompat.scanLine(), infoX, infoY, COLOR_DIM);
        label(graphics, "动画管线：" + EmfBridge.statusLine() + " · " + PunchyBridge.shortStatus(), infoX, infoY + 13,
                VjjCompat.emf() || PunchyBridge.isLoaded() ? COLOR_OK : COLOR_DIM);
        hint(graphics, "Esc 关闭 · 修改需点「保存」才写入 config · 未装外部动画时这些开关无副作用");
    }

    @Override
    protected boolean onButton(String id) {
        VjjSettings settings = VjjEditorState.get().settings();
        switch (id) {
            case "settings:force" -> VjjUiClient.send("set_force");
            case "settings:lock" -> VjjUiClient.send("set_lock");
            case "settings:hide" -> VjjUiClient.send("set_hide");
            case "settings:dummy" -> VjjUiClient.send("set_dummy");
            case "settings:broadcast" -> VjjUiClient.send("set_broadcast");
            case "settings:zoom_enabled" -> VjjUiClient.send("set_zoom_enabled");
            case "settings:zoom_down" -> VjjUiClient.sendAction("set_zoom", "", -1, 0, 0, 0, 0, 0, 0,
                    Math.max(1.0, settings.zoomFactor - 1.0), "", "");
            case "settings:zoom_up" -> VjjUiClient.sendAction("set_zoom", "", -1, 0, 0, 0, 0, 0, 0,
                    Math.min(20.0, settings.zoomFactor + 1.0), "", "");
            case "settings:emf_bridge" -> VjjUiClient.send("set_emf_bridge");
            case "settings:pose_priority" -> VjjUiClient.send("set_pose_priority");
            case "settings:weight_down" -> VjjUiClient.sendAction("set_overlay_weight", "", -1, 0, 0, 0, 0, 0, 0,
                    Math.max(0.2, settings.overlayWeight - 0.1), "", "");
            case "settings:weight_up" -> VjjUiClient.sendAction("set_overlay_weight", "", -1, 0, 0, 0, 0, 0, 0,
                    Math.min(1.0, settings.overlayWeight + 0.1), "", "");
            case "settings:save" -> VjjUiClient.send("save");
            case "settings:reload" -> VjjUiClient.send("reload");
            case "settings:back" -> this.minecraft.gui.setScreen(new VjjHubScreen());
            case "settings:close" -> this.onClose();
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
