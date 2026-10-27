package com.vjj.client.ui;

import com.vjj.client.VjjUiClient;
import com.vjj.client.camera.VjjCameraClient;
import com.vjj.client.camera.VjjZoomClient;
import com.vjj.client.state.VjjEditorState;
import com.vjj.store.CameraClip;
import com.vjj.store.CameraKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运镜编辑器（可视化）：左侧镜头列表（可点选），右侧选中镜头的关键帧列表（可点选）与参数 / 操作按钮。
 *
 * <p>能力：新建/删除镜头、捕获当前机位为关键帧、删除关键帧、调时长、调关键帧 FOV / 缓动、
 * 播放/停止、播放完整场景（镜头+假人+锁行动+隐藏）、保存到配置。</p>
 *
 * <p>按钮 id 契约（autotest/E2E 用，见 docs/VJJ-DESIGN.md §4）：
 * {@code camera:capture camera:key_del camera:duration_minus camera:duration_plus camera:new camera:delete
 * camera:play camera:stop camera:scene camera:save camera:back camera:close}（+ 若干扩展按钮）。</p>
 *
 * <p>所有编辑都只是“发动作”：服务端校验 OP + 改内存配置 + 回整包快照，界面下一帧按
 * {@link VjjEditorState} 重绘；“保存”才落盘 config/vjjnew/config.json。</p>
 */
public final class CameraEditorScreen extends VjjScreenBase {
    private static final int CLIP_ROW_HEIGHT = 13;
    private static final int KEY_ROW_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 4;
    private static final int BUTTON_ROWS = 5;
    private static final int MIN_DURATION = 20;
    private static final int MAX_DURATION = 6000;
    private static final int DURATION_STEP = 20;
    /** 左侧底部摘要高度。 */
    private static final int SUMMARY_HEIGHT = 56;

    /** 列表行点击区（自绘，不走按钮视觉）。 */
    private record Row(String id, int x, int y, int w, int h) {
    }

    private final List<Row> rows = new ArrayList<>();
    private int clipScroll;
    private int keyScroll;

    /** 最近一帧的布局（滚轮命中用）。 */
    private int leftPanelRight = 1;

    public CameraEditorScreen() {
        super(Component.translatable("vjj.ui.camera.title"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 绘制
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void renderContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        VjjEditorState state = VjjEditorState.get();
        rows.clear();

        drawHeader(graphics, "session=" + state.sessionId() + " · 镜头 " + state.clips().size()
                + " · 选中 " + (state.selectedClipId().isEmpty() ? "-" : state.selectedClipId()));

        int top = 30;
        int bottom = this.height - 22;
        int left = 8;
        int leftRight = Math.max(left + 108, (int) (this.width * 0.29));
        int rightLeft = leftRight + 5;
        int rightRight = Math.max(rightLeft + 200, this.width - 8);
        leftPanelRight = leftRight;

        panel(graphics, left, top, leftRight, bottom);
        panel(graphics, rightLeft, top, rightRight, bottom);

        drawClipList(graphics, state, left, top, leftRight, bottom);
        drawKeyList(graphics, state, rightLeft, top, rightRight, bottom);
        drawButtons(state, rightLeft, rightRight, bottom);

        hint(graphics, "点左侧镜头 / 右侧关键帧选中 · 先飞到想拍的机位再点「捕获」 · Z 按住变焦 · Esc 关闭");
    }

    private void drawClipList(GuiGraphicsExtractor graphics, VjjEditorState state,
                              int left, int top, int leftRight, int bottom) {
        List<CameraClip> clips = state.clips();
        label(graphics, "镜头 (" + clips.size() + ")", left + 6, top + 5, COLOR_TITLE);
        int rowX = left + 6;
        int rowW = Math.max(50, leftRight - left - 12);
        int rowY = top + 20;
        int available = Math.max(KEY_ROW_HEIGHT, bottom - rowY - SUMMARY_HEIGHT - 4);
        int visible = Math.max(1, available / CLIP_ROW_HEIGHT);
        int maxScroll = Math.max(0, clips.size() - visible);
        this.clipScroll = Math.clamp(this.clipScroll, 0, maxScroll);

        for (int i = 0; i < visible && this.clipScroll + i < clips.size(); i++) {
            int index = this.clipScroll + i;
            CameraClip clip = clips.get(index);
            boolean selected = clip.id != null && clip.id.equals(state.selectedClipId());
            int y = rowY + i * CLIP_ROW_HEIGHT;
            if (selected) {
                graphics.fill(rowX - 2, y - 1, rowX + rowW + 2, y + CLIP_ROW_HEIGHT - 2, 0x402E7A3F);
            }
            int keys = clip.keys == null ? 0 : clip.keys.size();
            String text = (selected ? "▶ " : "  ") + clip.id + " " + clip.durationTicks + "t/" + keys + "帧";
            label(graphics, this.font.plainSubstrByWidth(text, rowW - 2), rowX, y + 1,
                    selected ? COLOR_OK : COLOR_TEXT);
            rows.add(new Row("clip:" + clip.id, rowX - 2, y - 1, rowW + 4, CLIP_ROW_HEIGHT));
        }

        // 左侧底部：选中镜头 / 选中关键帧摘要
        int infoY = bottom - SUMMARY_HEIGHT + 2;
        CameraClip selected = state.selectedClip();
        label(graphics, this.font.plainSubstrByWidth("选中：" + (selected == null ? "-" : selected.id),
                rowW), left + 6, infoY, COLOR_DIM);
        if (selected != null) {
            label(graphics, this.font.plainSubstrByWidth("mode=" + selected.mode + " loop=" + selected.loop
                    + " " + selected.durationTicks + "t", rowW), left + 6, infoY + 10, COLOR_TEXT);
            CameraKey key = selectedKey(selected);
            if (key != null) {
                label(graphics, this.font.plainSubstrByWidth("#" + state.selectedKeyIndex() + " t=" + key.tick
                        + " fov=" + fmt(key.fov) + " " + key.easeOrDefault(), rowW),
                        left + 6, infoY + 20, COLOR_OK);
                label(graphics, this.font.plainSubstrByWidth("pos=" + fmt(key.x) + "," + fmt(key.y) + ","
                        + fmt(key.z), rowW), left + 6, infoY + 30, COLOR_DIM);
                label(graphics, this.font.plainSubstrByWidth("yaw=" + fmt(key.yaw) + " pitch=" + fmt(key.pitch),
                        rowW), left + 6, infoY + 40, COLOR_DIM);
            } else {
                label(graphics, "无选中帧", left + 6, infoY + 20, COLOR_DIM);
            }
        }
    }

    private void drawKeyList(GuiGraphicsExtractor graphics, VjjEditorState state,
                             int rightLeft, int top, int rightRight, int bottom) {
        CameraClip clip = state.selectedClip();
        int keys = clip == null || clip.keys == null ? 0 : clip.keys.size();
        label(graphics, this.font.plainSubstrByWidth(clip == null
                        ? "关键帧（未选中镜头）" : "关键帧（" + clip.id + "，" + keys + " 帧）",
                rightRight - rightLeft - 16), rightLeft + 6, top + 5, COLOR_TITLE);
        if (clip == null) {
            label(graphics, "先选中/新建一段镜头", rightLeft + 6, top + 20, COLOR_DIM);
            return;
        }
        label(graphics, this.font.plainSubstrByWidth("mode=" + clip.mode + " " + clip.durationTicks + "t · "
                        + (VjjCameraClient.isActive() ? "播放中 t=" + clipProgressText() : "空闲")
                        + " · 变焦 " + fmt(VjjZoomClient.currentFactor()) + "x",
                rightRight - rightLeft - 16), rightLeft + 6, top + 19, COLOR_DIM);

        List<CameraKey> keyList = clip.keys == null ? List.of() : clip.keys;
        int rowX = rightLeft + 6;
        int rowW = Math.max(80, rightRight - rightLeft - 12);
        int rowY = top + 32;
        int buttonsTop = buttonsTop(bottom);
        int available = Math.max(KEY_ROW_HEIGHT, buttonsTop - rowY - 12);
        int visible = Math.max(1, available / KEY_ROW_HEIGHT);
        int maxScroll = Math.max(0, keyList.size() - visible);
        this.keyScroll = Math.clamp(this.keyScroll, 0, maxScroll);

        for (int i = 0; i < visible && this.keyScroll + i < keyList.size(); i++) {
            int index = this.keyScroll + i;
            CameraKey key = keyList.get(index);
            boolean selected = index == state.selectedKeyIndex();
            int y = rowY + i * KEY_ROW_HEIGHT;
            if (selected) {
                graphics.fill(rowX - 2, y - 1, rowX + rowW + 2, y + KEY_ROW_HEIGHT - 1, 0x402E7A3F);
            }
            String text = (selected ? "▶ #" : "  #") + index + " t=" + key.tick
                    + " fov=" + fmt(key.fov) + " " + key.easeOrDefault();
            label(graphics, this.font.plainSubstrByWidth(text, rowW - 2), rowX, y + 1,
                    selected ? COLOR_OK : COLOR_TEXT);
            rows.add(new Row("key:" + index, rowX - 2, y - 1, rowW + 4, KEY_ROW_HEIGHT));
        }

        int footerY = buttonsTop - 11;
        if (keyList.size() > visible) {
            label(graphics, "滚轮翻页 " + (this.keyScroll + 1) + "-"
                            + Math.min(keyList.size(), this.keyScroll + visible) + "/" + keyList.size(),
                    rightLeft + 6, footerY, COLOR_DIM);
        } else if (keyList.isEmpty()) {
            label(graphics, "无关键帧：点「捕获」添加第一帧", rightLeft + 6, footerY, COLOR_DIM);
        } else if (VjjCameraClient.isActive()) {
            label(graphics, "▶ 播放中 " + VjjCameraClient.clipTicksText(), rightLeft + 6, footerY, COLOR_OK);
        }
    }

    private void drawButtons(VjjEditorState state, int rightLeft, int rightRight, int bottom) {
        clearButtons();
        int width = rightRight - rightLeft;
        int bw = Math.max(70, Math.min(150, (width - 16 - 2 * BUTTON_GAP) / 3));
        int col0 = rightLeft + 6;
        int col1 = col0 + bw + BUTTON_GAP;
        int col2 = col1 + bw + BUTTON_GAP;
        int row0 = buttonsTop(bottom);
        int r1 = row0 + BUTTON_HEIGHT + BUTTON_GAP;
        int r2 = r1 + BUTTON_HEIGHT + BUTTON_GAP;
        int r3 = r2 + BUTTON_HEIGHT + BUTTON_GAP;
        int r4 = r3 + BUTTON_HEIGHT + BUTTON_GAP;

        CameraClip clip = state.selectedClip();
        String clipId = clip == null || clip.id == null ? "" : clip.id;
        CameraKey key = selectedKey(clip);

        button("camera:capture", "捕获", col0, row0, bw, BUTTON_HEIGHT);
        button("camera:key_del", key != null ? "删帧 #" + state.selectedKeyIndex() : "删帧",
                col1, row0, bw, BUTTON_HEIGHT);
        button("camera:ease", key == null ? "缓动" : "缓动 " + key.easeOrDefault(),
                col2, row0, bw, BUTTON_HEIGHT);

        button("camera:duration_minus", "时长 -" + DURATION_STEP, col0, r1, bw, BUTTON_HEIGHT);
        button("camera:duration_plus", "时长 +" + DURATION_STEP, col1, r1, bw, BUTTON_HEIGHT);
        button("camera:key_fov_plus", "FOV +5", col2, r1, bw, BUTTON_HEIGHT);

        button("camera:new", "新建镜头", col0, r2, bw, BUTTON_HEIGHT);
        button("camera:delete", "删除镜头", col1, r2, bw, BUTTON_HEIGHT);
        button("camera:key_fov_minus", "FOV -5", col2, r2, bw, BUTTON_HEIGHT);

        button("camera:play", "播放", col0, r3, bw, BUTTON_HEIGHT);
        button("camera:stop", "停止", col1, r3, bw, BUTTON_HEIGHT);
        button("camera:scene", "完整场景", col2, r3, bw, BUTTON_HEIGHT);

        button("camera:save", "保存", col0, r4, bw, BUTTON_HEIGHT);
        button("camera:back", "返回主页", col1, r4, bw, BUTTON_HEIGHT);
        button("camera:close", "关闭", col2, r4, bw, BUTTON_HEIGHT);
    }

    /** 按钮区第一行的 y（关键帧列表据此留白）。 */
    private static int buttonsTop(int bottom) {
        return bottom - 4 - (BUTTON_ROWS * BUTTON_HEIGHT + (BUTTON_ROWS - 1) * BUTTON_GAP);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 交互
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 26.3 用 SDL 键值：鼠标左键 = 1
        if (event.button() == 1) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            for (Row row : rows) {
                if (inside(mouseX, mouseY, row.x(), row.y(), row.x() + row.w(), row.y() + row.h())) {
                    handleRow(row.id());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void handleRow(String rowId) {
        VjjEditorState state = VjjEditorState.get();
        if (rowId.startsWith("clip:")) {
            String clipId = rowId.substring("clip:".length());
            state.selectClip(clipId);
            VjjUiClient.sendUiEvent("select-clip", clipId);
        } else if (rowId.startsWith("key:")) {
            try {
                int index = Integer.parseInt(rowId.substring("key:".length()));
                state.selectKey(index);
                VjjUiClient.sendUiEvent("select-key", Integer.toString(index));
            } catch (NumberFormatException ignored) {
                // 行 id 由本类生成，理论上不会发生
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) {
            return false;
        }
        int delta = scrollY > 0.0 ? -1 : 1;
        if (mouseX < leftPanelRight) {
            clipScroll = Math.max(0, clipScroll + delta);
        } else {
            keyScroll = Math.max(0, keyScroll + delta);
        }
        return true;
    }

    @Override
    protected boolean onButton(String id) {
        VjjEditorState state = VjjEditorState.get();
        CameraClip clip = state.selectedClip();
        String clipId = clip == null || clip.id == null ? "" : clip.id;
        Minecraft minecraft = Minecraft.getInstance();
        switch (id) {
            case "camera:capture": {
                if (clip == null || minecraft.player == null) {
                    return true;
                }
                Vec3 eye = minecraft.player.getEyePosition();
                float yaw = minecraft.player.getYRot();
                float pitch = minecraft.player.getXRot();
                Vec3 capturePos = eye;
                try {
                    // 捕获“当前真实相机位置”（第三人称时在玩家身后，第一人称=眼睛）；
                    // 服务端按 clip.mode 换算：relative=相机-眼睛 的偏移，world=绝对坐标。
                    net.minecraft.client.Camera camera = minecraft.gameRenderer.mainCamera();
                    if (camera != null && camera.isInitialized()) {
                        capturePos = camera.position();
                        yaw = camera.yRot();
                        pitch = camera.xRot();
                    }
                } catch (RuntimeException ignored) {
                    // 相机还没初始化（界面在加载时打开）：退回玩家眼睛
                }
                CameraKey key = selectedKey(clip);
                int tick = key != null ? key.tick : Math.max(0, clip.durationTicks / 2);
                VjjUiClient.sendCapturePose(clipId, tick, capturePos.x, capturePos.y, capturePos.z, yaw, pitch, 70.0);
                return true;
            }
            case "camera:key_del": {
                if (clip != null && state.selectedKeyIndex() >= 0) {
                    VjjUiClient.sendAction("key_del", clipId, state.selectedKeyIndex(), 0,
                            0, 0, 0, 0, 0, 0, "", "");
                }
                return true;
            }
            case "camera:ease": {
                if (clip != null && state.selectedKeyIndex() >= 0) {
                    VjjUiClient.sendAction("key_ease", clipId, state.selectedKeyIndex(), 0,
                            0, 0, 0, 0, 0, 0, "", "");
                }
                return true;
            }
            case "camera:duration_minus": {
                if (clip != null) {
                    int next = Math.clamp(clip.durationTicks - DURATION_STEP, MIN_DURATION, MAX_DURATION);
                    VjjUiClient.sendAction("clip_duration", clipId, next, 0, 0, 0, 0, 0, 0, 0, "", "");
                }
                return true;
            }
            case "camera:duration_plus": {
                if (clip != null) {
                    int next = Math.clamp(clip.durationTicks + DURATION_STEP, MIN_DURATION, MAX_DURATION);
                    VjjUiClient.sendAction("clip_duration", clipId, next, 0, 0, 0, 0, 0, 0, 0, "", "");
                }
                return true;
            }
            case "camera:key_fov_plus":
            case "camera:key_fov_minus": {
                CameraKey key = selectedKey(clip);
                if (key != null) {
                    double delta = "camera:key_fov_plus".equals(id) ? 5.0 : -5.0;
                    double next = Math.clamp(key.fov + delta, 20.0, 110.0);
                    VjjUiClient.sendAction("key_fov", clipId, state.selectedKeyIndex(), 0,
                            0, 0, 0, 0, 0, next, "", "");
                }
                return true;
            }
            case "camera:new": {
                VjjUiClient.sendAction("clip_new", "", -1, 0, 0, 0, 0, 0, 0, 0, "", "");
                return true;
            }
            case "camera:delete": {
                if (clip != null) {
                    VjjUiClient.sendAction("clip_delete", clipId, -1, 0, 0, 0, 0, 0, 0, 0, "", "");
                    state.selectClip("");
                }
                return true;
            }
            case "camera:play": {
                if (clip != null) {
                    VjjUiClient.sendAction("clip_play", clipId, -1, 0, 0, 0, 0, 0, 0, 0, "", "");
                }
                return true;
            }
            case "camera:stop": {
                VjjUiClient.send("clip_stop");
                return true;
            }
            case "camera:scene": {
                if (clip != null) {
                    VjjUiClient.sendAction("scene_play", clipId, -1, 0, 0, 0, 0, 0, 0, 0,
                            clipId, state.settings().defaultAnim);
                }
                return true;
            }
            case "camera:save": {
                VjjUiClient.send("save");
                return true;
            }
            case "camera:back": {
                this.minecraft.gui.setScreen(new VjjHubScreen());
                return true;
            }
            case "camera:close": {
                this.onClose();
                return true;
            }
            default:
                return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────

    private static CameraKey selectedKey(CameraClip clip) {
        VjjEditorState state = VjjEditorState.get();
        if (clip == null || clip.keys == null) {
            return null;
        }
        int index = state.selectedKeyIndex();
        if (index < 0 || index >= clip.keys.size()) {
            return null;
        }
        return clip.keys.get(index);
    }

    private static String clipProgressText() {
        return String.format(Locale.ROOT, "%.2f", VjjCameraClient.progress());
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
