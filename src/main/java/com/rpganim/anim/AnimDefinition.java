package com.rpganim.anim;

import java.util.List;
import java.util.Map;

/**
 * 一条玩家/战斗动作的完整定义（从 content.json 解析；服务端加载后整包同步给客户端）。
 *
 * @param id             动作 id（全局唯一；命令 / 网络/ 日志都用它）
 * @param nameKey        翻译键（HUD/命令显示）
 * @param category       分类："emote" / "combat" / "hit"（自定义分类也会被原样保留）
 * @param durationTicks  时长（tick）
 * @param loop           是否循环（循环动作需要显式停止；非循环到期自动结束）
 * @param priority       优先级（大者优先；同优先级替换）
 * @param blendInTicks   进入混合时长
 * @param blendOutTicks  退出混合时长
 * @param rig            绑定的模型 rig id（必须是 rpgmodel 提供的 rig）
 * @param bones          骨骼名 -> 轨道（bones() 顺序稳定）
 */
public record AnimDefinition(String id,
                             String nameKey,
                             String category,
                             int durationTicks,
                             boolean loop,
                             int priority,
                             int blendInTicks,
                             int blendOutTicks,
                             String rig,
                             Map<String, BoneTrack> bones) {

    public static final String CATEGORY_EMOTE = "emote";
    public static final String CATEGORY_COMBAT = "combat";
    public static final String CATEGORY_HIT = "hit";

    public AnimDefinition {
        bones = bones == null ? Map.of() : Map.copyOf(bones);
    }

    public boolean isCombat() {
        return CATEGORY_COMBAT.equals(category);
    }

    public boolean isHit() {
        return CATEGORY_HIT.equals(category);
    }

    public boolean isEmote() {
        return CATEGORY_EMOTE.equals(category);
    }

    /**
     * 客户端用：该动作是否应该压制原版挥臂动画（否则我们的战斗动作会和原版挥臂叠加）。
     * 满足条件时 mixin 会取消 {@code HumanoidModel.setupAttackAnimation}。
     */
    public boolean suppressesVanillaAttack() {
        return isCombat() || isHit();
    }

    /** 该动作覆盖到的最小点（公共信息，便于日志/调试）。 */
    public int boneCount() {
        return bones.size();
    }

    /** 骨骼名列表（稳定顺序，用于 rig 校验/日志）。 */
    public List<String> boneNames() {
        return List.copyOf(bones.keySet());
    }
}
