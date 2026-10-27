package com.vjj.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一段可编辑运镜（VJJ-NEW 镜头编辑器的数据单位）。
 *
 * <p>与 rpgcam 内置的 13 个样式（{@code CueRegistry}）并存：样式是“程序化轨迹”，
 * 本类是“关键帧轨迹”，由 OP 在可视化界面里创建 / 捕获 / 调整 / 保存。</p>
 */
public final class CameraClip {
    /** 镜头 id（保存到 config，命令与网络包都用它引用）。 */
    public String id = "";
    /** 显示名（GUI 列表用）。 */
    public String name = "";
    /** 整段时长（tick，20t = 1s）。 */
    public int durationTicks = 200;
    public boolean loop = false;
    /** {@code relative}（默认，相对锚点眼睛）或 {@code world}（世界绝对坐标）。 */
    public String mode = "relative";
    /** 关键帧（按 tick 升序；保存/播放前会排序）。 */
    public List<CameraKey> keys = new ArrayList<>();

    public CameraClip() {
    }

    public CameraClip(String id, String name) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
    }

    public boolean isRelative() {
        return !"world".equalsIgnoreCase(this.mode == null ? "" : this.mode);
    }

    public CameraClip copy() {
        CameraClip copy = new CameraClip(this.id, this.name);
        copy.durationTicks = this.durationTicks;
        copy.loop = this.loop;
        copy.mode = this.mode;
        copy.keys = new ArrayList<>();
        if (this.keys != null) {
            for (CameraKey key : this.keys) {
                if (key != null) {
                    copy.keys.add(key.copy());
                }
            }
        }
        copy.sortKeys();
        return copy;
    }

    /** 关键帧按 tick 排序（稳定，同 tick 保留原顺序）。 */
    public void sortKeys() {
        if (this.keys == null) {
            this.keys = new ArrayList<>();
            return;
        }
        this.keys.sort(Comparator.comparingInt(k -> k.tick));
    }

    /** 去掉空帧并把 tick 钳到 [0, durationTicks]，再排序。 */
    public void normalize() {
        if (this.durationTicks < 1) {
            this.durationTicks = 1;
        }
        if (this.keys == null) {
            this.keys = new ArrayList<>();
        }
        this.keys.removeIf(key -> key == null);
        for (CameraKey key : this.keys) {
            key.tick = Math.clamp(key.tick, 0, this.durationTicks);
            if (key.ease == null || key.ease.isBlank()) {
                key.ease = "smooth";
            }
            if (key.fov <= 0.0F) {
                key.fov = 70.0F;
            }
        }
        sortKeys();
    }

    /** 保证至少有一个关键帧：没有时补一个起点帧（相对模式默认在眼睛前方 3 格）。 */
    public void ensureUsable() {
        normalize();
        if (this.keys.isEmpty()) {
            this.keys.add(this.isRelative()
                    ? new CameraKey(0, 0.0, 0.0, 3.0, 0.0F, 0.0F, 70.0F, "smooth")
                    : new CameraKey(0, 0.0, 64.0, 0.0, 0.0F, 0.0F, 70.0F, "smooth"));
        }
    }

    @Override
    public String toString() {
        return "clip[" + this.id + " name=" + this.name + " duration=" + this.durationTicks
                + "t loop=" + this.loop + " mode=" + this.mode + " keys=" + (this.keys == null ? 0 : this.keys.size()) + "]";
    }
}
