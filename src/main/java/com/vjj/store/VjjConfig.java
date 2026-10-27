package com.vjj.store;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/vjjnew/config.json} 的根对象：一份默认设置 + 全部可编辑运镜。
 *
 * <p>注意：格式一旦发布就要向后兼容；{@code format} 用于未来迁移。</p>
 */
public final class VjjConfig {
    public static final int CURRENT_FORMAT = 1;

    public int format = CURRENT_FORMAT;
    public VjjSettings settings = new VjjSettings();
    public List<CameraClip> clips = new ArrayList<>();

    public CameraClip findClip(String id) {
        if (id == null || this.clips == null) {
            return null;
        }
        for (CameraClip clip : this.clips) {
            if (clip != null && id.equals(clip.id)) {
                return clip;
            }
        }
        return null;
    }

    /** 保证对象可用：设置合法、clips 非 null、每段镜头有 id 且至少一个关键帧。 */
    public void normalize() {
        if (this.settings == null) {
            this.settings = new VjjSettings();
        }
        this.settings.normalize();
        if (this.clips == null) {
            this.clips = new ArrayList<>();
        }
        this.clips.removeIf(clip -> clip == null);
        for (CameraClip clip : this.clips) {
            if (clip.id == null || clip.id.isBlank()) {
                clip.id = "clip_" + Integer.toHexString(System.identityHashCode(clip));
            }
            if (clip.name == null || clip.name.isBlank()) {
                clip.name = clip.id;
            }
            clip.ensureUsable();
        }
        if (this.format < CURRENT_FORMAT) {
            this.format = CURRENT_FORMAT;
        }
    }
}
