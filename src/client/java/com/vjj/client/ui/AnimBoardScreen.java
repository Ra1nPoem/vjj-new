package com.vjj.client.ui;

import com.rpganim.anim.AnimContent;
import com.rpganim.anim.AnimDefinition;
import com.rpganim.client.ClientAnimState;
import com.vjj.client.VjjUiClient;
import com.vjj.client.state.VjjEditorState;
import com.vjj.store.VjjSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 动画板块（可视化 OP 界面）：选动作/目标，播放真实玩家动作或生成「玩家假人」替代本体播放。
 *
 * <p>按钮 id 契约（autotest / E2E 用，不能改）：</p>
 * <pre>
 * anim:play  anim:stop  anim:dummy_spawn  anim:dummy_remove  anim:target  anim:save  anim:back  anim:close
 * 动作列表行：anim:select:&lt;animId&gt;（本地选择态，不发服务端动作；只发一条 ui-event 取证）
 * </pre>
 *
 * <p>所有按钮点击都只是「发动作」：动作播放/假人生成/清理/保存由服务端校验 OP+session 后执行，
 * 服务端回 {@code EditorDataPayload} 整包快照，界面下一帧原位重绘（不在客户端缓存状态）。</p>
 */
public final class AnimBoardScreen extends VjjScreenBase {
    /** 动作列表最多展示行数（rpganim 目前 14 个动作，留余量）。 */
    private static final int MAX_ROWS = 16;

    public AnimBoardScreen() {
        super(Component.translatable("vjj.ui.anim.title"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 绘制
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        VjjEditorState state = VjjEditorState.get();
        VjjSettings settings = state.settings();
        drawHeader(graphics, "session=" + state.sessionId() + " 动作=" + state.anims().size()
                + " 在线=" + state.targets().size());

        int top = 40;
        int bottom = this.height - 28;
        int leftWidth = Math.max(210, (int) (this.width * 0.34));
        int rightX = leftWidth + 8;
        panel(graphics, 16, top, leftWidth, bottom);
        panel(graphics, rightX, top, this.width - 16, bottom);

        // ── 左侧：动作列表 ──────────────────────────────────────────────
        label(graphics, "动作列表（" + state.anims().size() + "）· 点击选择", 24, top + 8, COLOR_TITLE);
        clearButtons();
        List<String> anims = state.anims();
        int rowY = top + 24;
        for (int i = 0; i < anims.size() && i < MAX_ROWS; i++) {
            String animId = anims.get(i);
            boolean selected = animId.equals(state.selectedAnimId());
            button("anim:select:" + animId, (selected ? "▶ " : "   ") + animId + infoSuffix(animId),
                    24, rowY + i * 16, leftWidth - 32, 14);
        }
        if (anims.isEmpty()) {
            label(graphics, "（没有动画：等待 rpganim 内容同步）", 24, rowY + 4, COLOR_DIM);
        }
        this.activeButtonId = state.selectedAnimId().isEmpty() ? ""
                : "anim:select:" + state.selectedAnimId();
        label(graphics, "共 " + anims.size() + " 个动作 · 来自 rpganim content.json", 24, bottom - 16, COLOR_DIM);

        // ── 右侧：选中动作 / 目标 / 设置 ─────────────────────────────────
        int x = rightX + 14;
        int infoY = top + 10;
        label(graphics, "选中动作：" + (state.selectedAnimId().isEmpty() ? "-" : state.selectedAnimId()),
                x, infoY, COLOR_TITLE);
        label(graphics, "动作信息：" + animInfo(state.selectedAnimId()), x, infoY + 16, COLOR_TEXT);
        label(graphics, "目标玩家：" + (state.targetName().isEmpty() ? "自己（OP）" : state.targetName()),
                x, infoY + 32, COLOR_TITLE);
        label(graphics, "假人替代：" + onOff(settings.spawnDummy)
                        + " · 隐藏本体：" + onOff(settings.hidePlayer)
                        + " · 锁行动：" + onOff(settings.lockAction),
                x, infoY + 48, COLOR_DIM);
        label(graphics, "广播镜头：" + onOff(settings.broadcastCamera)
                        + " · 变焦：" + (settings.zoomEnabled ? "开 " + settings.zoomFactor + "x" : "关")
                        + " · 默认动作：" + settings.defaultAnim,
                x, infoY + 62, COLOR_DIM);
        label(graphics, "状态：" + state.status(), x, bottom - 18, COLOR_OK);

        // ── 右侧：操作按钮（2 列 4 行）─────────────────────────────────
        int by = infoY + 90;
        int w = 170;
        int h = 22;
        int gap = 8;
        int col2 = x + w + gap;
        button("anim:play", "播放动作给目标", x, by, w, h);
        button("anim:stop", "停止目标动作", col2, by, w, h);
        button("anim:dummy_spawn", "生成假人并播放", x, by + (h + gap), w, h);
        button("anim:dummy_remove", "移除目标假人", col2, by + (h + gap), w, h);
        button("anim:target", "切换目标玩家", x, by + 2 * (h + gap), w, h);
        button("anim:save", "保存配置", col2, by + 2 * (h + gap), w, h);
        button("anim:back", "返回主页", x, by + 3 * (h + gap), w, h);
        button("anim:close", "关闭界面", col2, by + 3 * (h + gap), w, h);

        int hintY = by + 4 * (h + gap);
        label(graphics, "「生成假人」= 在目标位置创建玩家假人（目标皮肤 + 骨骼动画）；"
                + "「播放动作」= 直接驱动真实玩家模型。", x, hintY, COLOR_DIM);
        label(graphics, "假人使用目标玩家皮肤；建议切第三人称（F5）观察。所有修改需点「保存」才写入 config。",
                x, hintY + 14, COLOR_DIM);

        hint(graphics, "动画来自 rpganim 数据（14 个动作）· 假人实体 vjjnew:dummy · Esc 关闭");
    }

    private static String onOff(boolean value) {
        return value ? "开" : "关";
    }

    /** 列表行的短后缀：时长 + 是否循环（内容已 sync 时才有）。 */
    private static String infoSuffix(String animId) {
        AnimDefinition def = definition(animId);
        if (def == null) {
            return "";
        }
        return "  (" + def.durationTicks() + "t" + (def.loop() ? "·循环" : "") + ")";
    }

    /** 右侧完整动作信息（分类 / 时长 / 循环 / 骨骼数）。 */
    private static String animInfo(String animId) {
        if (animId == null || animId.isEmpty()) {
            return "未选择";
        }
        AnimDefinition def = definition(animId);
        if (def == null) {
            return "未同步（等待 rpganim content sync）";
        }
        return "分类=" + def.category() + " · 时长=" + def.durationTicks() + "t · 循环=" + def.loop()
                + " · 骨骼=" + def.bones().size();
    }

    private static AnimDefinition definition(String animId) {
        if (animId == null || animId.isEmpty()) {
            return null;
        }
        AnimContent content = ClientAnimState.content();
        return content == null ? null : content.animation(animId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 按钮动作
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected boolean onButton(String id) {
        VjjEditorState state = VjjEditorState.get();
        String anim = state.selectedAnimId();
        String target = state.targetName();
        if (id.startsWith("anim:select:")) {
            String animId = id.substring("anim:select:".length());
            state.selectAnim(animId);
            VjjUiClient.sendUiEvent("anim-select", animId);
            return true;
        }
        switch (id) {
            case "anim:play" -> {
                // s1 = 动作 id，s2 = 目标玩家名（空 = OP 自己）
                VjjUiClient.sendAction("anim_play", "", -1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, anim, target);
                return true;
            }
            case "anim:stop" -> {
                VjjUiClient.sendAction("anim_stop", "", -1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "", target);
                return true;
            }
            case "anim:dummy_spawn" -> {
                // s1 = 动作 id，s2 = 目标玩家名；tick=1 表示循环播放（与 /vjj dummy spawn 一致）
                VjjUiClient.sendAction("dummy_spawn", "", -1, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, anim, target);
                return true;
            }
            case "anim:dummy_remove" -> {
                VjjUiClient.sendAction("dummy_remove", "", -1, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "", target);
                return true;
            }
            case "anim:target" -> {
                state.cycleTarget();
                VjjUiClient.sendUiEvent("anim-target", state.targetName());
                return true;
            }
            case "anim:save" -> {
                VjjUiClient.send("save");
                return true;
            }
            case "anim:back" -> {
                this.minecraft.gui.setScreen(new VjjHubScreen());
                return true;
            }
            case "anim:close" -> {
                this.onClose();
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
