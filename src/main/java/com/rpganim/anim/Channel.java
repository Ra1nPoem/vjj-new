package com.rpganim.anim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一根骨骼上的一个通道（rotation / position / scale）的关键帧序列。
 * 采样是纯函数、线程安全：给定动作内时间（tick）返回 3 分量。
 */
public record Channel(String kind, List<Keyframe> frames) {
    public static final String ROTATION = "rotation";
    public static final String POSITION = "position";
    public static final String SCALE = "scale";

    public Channel {
        List<Keyframe> sorted = new ArrayList<>(frames == null ? List.of() : frames);
        sorted.sort(Comparator.comparingDouble(Keyframe::tick));
        frames = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public float startTick() {
        return frames.isEmpty() ? 0.0F : frames.get(0).tick();
    }

    public float endTick() {
        return frames.isEmpty() ? 0.0F : frames.get(frames.size() - 1).tick();
    }

    /**
     * 在动作内时间 {@code t} 处采样；首帧之前/末帧之后钳制到端点值。
     *
     * @return 长度为 3 的分量数组（不返回 null；空通道返回全 0）
     */
    public float[] sample(float t) {
        if (frames.isEmpty()) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        Keyframe first = frames.get(0);
        if (t <= first.tick()) {
            return new float[] {first.x(), first.y(), first.z()};
        }
        Keyframe last = frames.get(frames.size() - 1);
        if (t >= last.tick()) {
            return new float[] {last.x(), last.y(), last.z()};
        }
        for (int i = 0; i < frames.size() - 1; i++) {
            Keyframe a = frames.get(i);
            Keyframe b = frames.get(i + 1);
            if (t >= a.tick() && t <= b.tick()) {
                float span = b.tick() - a.tick();
                float u = span <= 0.0F ? 0.0F : (t - a.tick()) / span;
                float e = (b.smooth() || a.smooth()) ? b.ease(u) : u;
                return new float[] {
                        a.x() + (b.x() - a.x()) * e,
                        a.y() + (b.y() - a.y()) * e,
                        a.z() + (b.z() - a.z()) * e
                };
            }
        }
        return new float[] {last.x(), last.y(), last.z()};
    }
}
