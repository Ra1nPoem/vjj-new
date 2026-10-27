package com.rpgcam.cue;

/**
 * 一个镜头样式的完整参数（服务端与客户端共用同一份，服务端权威、客户端只按它演算）。
 *
 * <p>角度约定与 Minecraft 的 yaw 一致：yaw=0 朝 +Z（南），yaw=90 朝 -X（西）。
 * 相机世界位置 = 锚点 + 方向(startAngle + sweep * progress) * 距离 + 高度偏移。
 * 锚点是实体时，startAngle 会被解释为「相对该实体朝向的偏移角」，因此同一套参数对任意朝向都成立。</p>
 *
 * @param id                样式 id（{@code /cinematic play <id>}）
 * @param titleKey          双语标题 lang key
 * @param motion            运动类型
 * @param anchor            锚点类型
 * @param look              注视点类型
 * @param durationTicks     默认时长
 * @param startDist         起始距离
 * @param endDist           结束距离
 * @param startAngleDeg     相对实体朝向的起始方位角
 * @param sweepDeg          方位角扫过角度（ORBIT/HANDHELD 用）
 * @param startHeight       起始高度（相对锚点眼睛位置，米）
 * @param endHeight         结束高度
 * @param fovStart          起始 FOV（度；&lt;=0 表示不改）
 * @param fovEnd            结束 FOV
 * @param hideHud           是否在演出期间隐藏 HUD（含第一人称手/准星）
 * @param letterbox         上下黑边高度占屏幕比例（0~0.25）
 * @param fadeInTicks       从黑场淡入的 tick 数
 * @param fadeOutTicks      淡出到黑场的 tick 数
 * @param shake             起始冲击抖动强度（0 表示无）
 * @param drift             漂移幅度（米，HOVER/HANDHELD 用）
 */
public record CueSpec(
        String id,
        String titleKey,
        MotionType motion,
        AnchorType anchor,
        LookType look,
        int durationTicks,
        double startDist,
        double endDist,
        float startAngleDeg,
        float sweepDeg,
        float startHeight,
        float endHeight,
        float fovStart,
        float fovEnd,
        boolean hideHud,
        float letterbox,
        int fadeInTicks,
        int fadeOutTicks,
        float shake,
        float drift) {

    /** 该样式是否有可覆盖的 FOV。 */
    public boolean changesFov() {
        return fovStart > 0.0F || fovEnd > 0.0F;
    }
}
