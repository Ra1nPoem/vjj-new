package com.rpganim.anim;

import java.util.List;

/**
 * 一段过场动画：运镜 + 动作时间轴。
 *
 * @param id             过场 id
 * @param titleKey       过场标题翻译键（rpgcam 镜头 label 也用它）
 * @param durationTicks  过场总时长
 * @param camera         运镜（可为 null：无镜头，纯动作过场）
 * @param steps          动作步骤（按 at 升序）
 */
public record CutsceneDefinition(String id, String titleKey, int durationTicks,
                                 CutsceneCamera camera, List<CutsceneStep> steps) {

    public CutsceneDefinition {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean hasCamera() {
        return camera != null;
    }
}
