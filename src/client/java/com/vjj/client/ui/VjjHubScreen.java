package com.vjj.client.ui;

import com.vjj.client.VjjUiClient;
import com.vjj.client.state.VjjEditorState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * VJJ-NEW 可视化操作界面主页（OP 专用）：四个入口 —— 运镜编辑 / 动画板块 / 设置与保存 / 演示场景。
 *
 * <p>服务端通过 {@code EditorOpenPayload} 打开本界面；所有按钮都只是“发动作”，
 * 真正的修改/保存由服务端做（服务端再校验一次 OP 权限，客户端改 UI 不能提权）。</p>
 */
public final class VjjHubScreen extends VjjScreenBase {
    public VjjHubScreen() {
        super(Component.translatable("vjj.ui.hub.title"));
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        VjjEditorState state = VjjEditorState.get();
        drawHeader(graphics, "session=" + state.sessionId() + " op=" + state.op());
        int left = 24;
        int top = 44;
        int right = this.width - 24;
        int bottom = this.height - 28;
        panel(graphics, left, top, right, bottom);
        label(graphics, "VJJ-NEW · RPG 演出整合版", left + 12, top + 12, COLOR_TITLE);
        label(graphics, "运镜(Camera) + 动画(Animation) + 模型(Rig) 三合一；可视化编辑由 OP 操作并保存。", left + 12,
                top + 28, COLOR_TEXT);
        label(graphics, "当前配置：镜头 " + state.clips().size() + " 段 / 动作 " + state.anims().size()
                + " 个 / 在线 " + state.targets().size() + " 人", left + 12, top + 42, COLOR_DIM);
        label(graphics, "状态：" + state.status(), left + 12, top + 56, COLOR_OK);

        clearButtons();
        int centerX = this.width / 2;
        int x = centerX - 132;
        int y = top + 84;
        int w = 264;
        int h = 24;
        int gap = 10;
        button("hub:camera", "运镜编辑（关键帧 / 变焦 / 捕获机位 / 播放）", x, y, w, h);
        button("hub:anim", "动画板块（播放动作 / 假人替代 / 目标选择）", x, y + (h + gap), w, h);
        button("hub:settings", "设置与保存（强制镜头 / 锁行动 / 隐藏玩家 / 保存）", x, y + 2 * (h + gap), w, h);
        button("hub:scene", "播放演示场景（镜头 + 假人 + 锁行动）", x, y + 3 * (h + gap), w, h);
        button("hub:save", "保存配置到 config/vjjnew/config.json", x, y + 4 * (h + gap), w, h);
        button("hub:close", "关闭", x, y + 5 * (h + gap), w, h);

        hint(graphics, "Esc 关闭 · 所有修改需要点「保存」才写入文件 · 界面只对 OP 开放");
    }

    @Override
    protected boolean onButton(String id) {
        VjjEditorState state = VjjEditorState.get();
        switch (id) {
            case "hub:camera" -> {
                VjjUiClient.sendUiEvent("tab", "camera");
                this.minecraft.gui.setScreen(new CameraEditorScreen());
                return true;
            }
            case "hub:anim" -> {
                VjjUiClient.sendUiEvent("tab", "anim");
                this.minecraft.gui.setScreen(new AnimBoardScreen());
                return true;
            }
            case "hub:settings" -> {
                VjjUiClient.sendUiEvent("tab", "settings");
                this.minecraft.gui.setScreen(new VjjSettingsScreen());
                return true;
            }
            case "hub:scene" -> {
                String clipId = state.selectedClipId().isEmpty() ? "demo" : state.selectedClipId();
                VjjUiClient.sendAction("scene_play", clipId, -1, 0, 0, 0, 0, 0, 0, 0,
                        clipId, state.settings().defaultAnim);
                return true;
            }
            case "hub:save" -> {
                VjjUiClient.send("save");
                return true;
            }
            case "hub:close" -> {
                this.onClose();
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
