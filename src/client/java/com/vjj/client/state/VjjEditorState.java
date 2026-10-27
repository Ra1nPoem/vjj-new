package com.vjj.client.state;

import com.vjj.store.CameraClip;
import com.vjj.store.VjjConfigStore;
import com.vjj.store.VjjSettings;
import com.vjj.net.VjjPayloads;
import com.vjj.client.VjjUiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 可视化面板的客户端状态（服务端 {@code EditorDataPayload} 到达后整体替换；界面只读它）。
 *
 * <p>界面不缓存自己的副本：任何动作由服务端处理并回一份新快照，界面在原位重绘，
 * 所以多端/命令/另一个 OP 同时改配置时不会出现「界面显示旧值」。</p>
 */
public final class VjjEditorState {
    private static final VjjEditorState INSTANCE = new VjjEditorState();

    private int sessionId;
    private boolean op;
    private List<CameraClip> clips = new ArrayList<>();
    private VjjSettings settings = new VjjSettings();
    private List<String> anims = new ArrayList<>();
    private List<String> targets = new ArrayList<>();
    private String status = "";
    /** 当前选中的镜头 id（客户端选择态，不持久化）。 */
    private String selectedClipId = "";
    private int selectedKeyIndex = -1;
    /** 动画板块当前选中的动画 id。 */
    private String selectedAnimId = "";
    /** 目标玩家名（空 = 自己）。 */
    private String targetName = "";

    private VjjEditorState() {
    }

    public static VjjEditorState get() {
        return INSTANCE;
    }

    public void apply(VjjPayloads.EditorDataPayload payload) {
        this.sessionId = payload.sessionId();
        CameraClip[] parsed = VjjConfigStore.parseClips(payload.clipsJson());
        List<CameraClip> next = new ArrayList<>();
        for (CameraClip clip : parsed) {
            if (clip != null) {
                next.add(clip);
            }
        }
        this.clips = next;
        this.settings = VjjConfigStore.parseSettings(payload.settingsJson());
        this.anims = parseStringArray(payload.animsJson());
        this.targets = parseStringArray(payload.targetsJson());
        this.status = payload.status() == null ? "" : payload.status();
        if (this.selectedClipId.isEmpty() || findClip(this.selectedClipId) == null) {
            this.selectedClipId = this.clips.isEmpty() ? "" : this.clips.get(0).id;
            this.selectedKeyIndex = -1;
        }
        if (this.selectedAnimId.isEmpty() && !this.anims.isEmpty()) {
            this.selectedAnimId = this.anims.get(0);
        }
        if (!this.targetName.isEmpty() && !this.targets.contains(this.targetName)) {
            this.targetName = "";
        }
        VjjUiClient.LOGGER.info(
                "[VjjNew] editor-data session={} clips={} anims={} targets={} selected={} status=\"{}\"",
                this.sessionId, this.clips.size(), this.anims.size(), this.targets.size(),
                this.selectedClipId, this.status);
    }

    public void reset() {
        this.sessionId = 0;
        this.op = false;
        this.clips = new ArrayList<>();
        this.settings = new VjjSettings();
        this.anims = new ArrayList<>();
        this.targets = new ArrayList<>();
        this.status = "";
        this.selectedClipId = "";
        this.selectedKeyIndex = -1;
        this.selectedAnimId = "";
        this.targetName = "";
    }

    public void setSession(int sessionId, boolean op) {
        this.sessionId = sessionId;
        this.op = op;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    public int sessionId() {
        return this.sessionId;
    }

    public boolean op() {
        return this.op;
    }

    public List<CameraClip> clips() {
        return this.clips;
    }

    public VjjSettings settings() {
        return this.settings;
    }

    public List<String> anims() {
        return this.anims;
    }

    public List<String> targets() {
        return this.targets;
    }

    public String status() {
        return this.status;
    }

    public String selectedClipId() {
        return this.selectedClipId;
    }

    public CameraClip selectedClip() {
        return findClip(this.selectedClipId);
    }

    public int selectedKeyIndex() {
        return this.selectedKeyIndex;
    }

    public String selectedAnimId() {
        return this.selectedAnimId;
    }

    public String targetName() {
        return this.targetName;
    }

    public CameraClip findClip(String id) {
        if (id == null) {
            return null;
        }
        for (CameraClip clip : this.clips) {
            if (id.equals(clip.id)) {
                return clip;
            }
        }
        return null;
    }

    // ── 客户端选择态 ─────────────────────────────────────────────────────

    public void selectClip(String clipId) {
        this.selectedClipId = clipId == null ? "" : clipId;
        this.selectedKeyIndex = -1;
    }

    public void selectKey(int index) {
        this.selectedKeyIndex = index;
    }

    public void selectAnim(String animId) {
        this.selectedAnimId = animId == null ? "" : animId;
    }

    public void cycleTarget() {
        if (this.targets.isEmpty()) {
            this.targetName = "";
            return;
        }
        if (this.targetName.isEmpty()) {
            this.targetName = this.targets.get(0);
            return;
        }
        int index = this.targets.indexOf(this.targetName);
        int next = index + 1;
        this.targetName = next >= this.targets.size() ? "" : this.targets.get(next);
    }

    private static List<String> parseStringArray(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            String[] parsed = new com.google.gson.Gson().fromJson(json, String[].class);
            if (parsed != null) {
                for (String value : parsed) {
                    if (value != null && !value.isBlank()) {
                        list.add(value);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 解析失败当作空列表，不崩界面
        }
        return list;
    }
}
