package com.rpgcam.cue;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置镜头样式目录（唯一的“内容源”）。服务端命令/API 与客户端演算都读这里。
 */
public final class CueRegistry {
    private static final Map<String, CueSpec> STYLES = new LinkedHashMap<>();
    private static boolean initialized;

    private CueRegistry() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        // 环绕展示：360° 绕玩家一圈
        add(new CueSpec("orbit", "cinematic.rpgcam.style.orbit", MotionType.ORBIT, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                140, 5.5, 5.5, 30.0F, 360.0F, 1.8F, 1.8F, 70.0F, 70.0F, true, 0.10F, 8, 8, 0.0F, 0.03F));
        // 推进特写
        add(new CueSpec("dolly_in", "cinematic.rpgcam.style.dolly_in", MotionType.DOLLY, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                110, 8.0, 2.8, 0.0F, 0.0F, 1.75F, 1.65F, 72.0F, 52.0F, true, 0.12F, 6, 8, 0.0F, 0.02F));
        // 拉远揭示
        add(new CueSpec("dolly_out", "cinematic.rpgcam.style.dolly_out", MotionType.DOLLY, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                100, 2.5, 9.0, 0.0F, 0.0F, 1.6F, 2.2F, 50.0F, 75.0F, true, 0.08F, 4, 6, 0.0F, 0.02F));
        // 摇臂上升，揭示场景
        add(new CueSpec("crane_up", "cinematic.rpgcam.style.crane_up", MotionType.CRANE, AnchorType.PLAYER, LookType.ANCHOR_CENTER,
                140, 4.5, 4.5, 25.0F, 0.0F, 0.2F, 7.0F, 72.0F, 62.0F, true, 0.10F, 8, 8, 0.0F, 0.02F));
        // 对话镜头：看向 NPC，从侧前方缓慢靠近
        add(new CueSpec("dialogue", "cinematic.rpgcam.style.dialogue", MotionType.DOLLY, AnchorType.TARGET, LookType.ANCHOR_EYE,
                120, 3.0, 2.4, 55.0F, 0.0F, 1.55F, 1.5F, 60.0F, 58.0F, true, 0.13F, 6, 6, 0.0F, 0.03F));
        // 首领登场：低角度推进 + 开场冲击抖动
        add(new CueSpec("boss_intro", "cinematic.rpgcam.style.boss_intro", MotionType.DOLLY, AnchorType.TARGET, LookType.ANCHOR_EYE,
                150, 8.0, 3.5, 0.0F, 0.0F, 1.3F, 1.6F, 78.0F, 58.0F, true, 0.14F, 12, 10, 0.12F, 0.01F));
        // 任务完成：从侧后方绕半圈升起
        add(new CueSpec("quest_complete", "cinematic.rpgcam.style.quest_complete", MotionType.ORBIT, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                100, 4.2, 4.2, 200.0F, 150.0F, 1.2F, 2.2F, 65.0F, 62.0F, true, 0.10F, 6, 8, 0.0F, 0.04F));
        // 奖励特写：轻微悬停
        add(new CueSpec("reward", "cinematic.rpgcam.style.reward", MotionType.HOVER, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                70, 3.2, 3.2, 160.0F, 0.0F, 1.7F, 1.4F, 58.0F, 58.0F, true, 0.08F, 3, 5, 0.0F, 0.03F));
        // 胜利时刻：环绕 + 拉高
        add(new CueSpec("victory", "cinematic.rpgcam.style.victory", MotionType.ORBIT, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                150, 4.0, 5.5, -45.0F, 360.0F, 1.0F, 3.2F, 70.0F, 58.0F, true, 0.12F, 8, 10, 0.0F, 0.03F));
        // 倒地镜头
        add(new CueSpec("death", "cinematic.rpgcam.style.death", MotionType.DROP, AnchorType.PLAYER, LookType.ANCHOR_CENTER,
                80, 3.2, 3.2, 140.0F, 0.0F, 1.8F, 0.35F, 72.0F, 82.0F, true, 0.16F, 0, 8, 0.0F, 0.02F));
        // 手持跟拍
        add(new CueSpec("handheld", "cinematic.rpgcam.style.handheld", MotionType.HANDHELD, AnchorType.PLAYER, LookType.ANCHOR_EYE,
                130, 2.6, 2.6, 120.0F, 15.0F, 1.55F, 1.55F, 72.0F, 72.0F, true, 0.06F, 4, 4, 0.0F, 0.09F));
        // 聚焦观察：把远处目标拉近（类望远镜）
        add(new CueSpec("zoom", "cinematic.rpgcam.style.zoom", MotionType.HOVER, AnchorType.TARGET, LookType.ANCHOR_EYE,
                100, 6.0, 6.0, 0.0F, 0.0F, 1.5F, 1.5F, 70.0F, 28.0F, true, 0.10F, 5, 7, 0.0F, 0.01F));
        // 降临/开场：从高空摇臂下降到人物
        add(new CueSpec("arrival", "cinematic.rpgcam.style.arrival", MotionType.CRANE, AnchorType.PLAYER, LookType.ANCHOR_CENTER,
                120, 5.0, 5.0, 210.0F, 0.0F, 12.0F, 2.0F, 80.0F, 68.0F, true, 0.12F, 15, 10, 0.0F, 0.02F));
    }

    private static void add(CueSpec spec) {
        STYLES.put(spec.id(), spec);
    }

    /** @return 样式；不存在返回 null。 */
    public static CueSpec get(String id) {
        init();
        return id == null ? null : STYLES.get(id);
    }

    public static boolean has(String id) {
        return get(id) != null;
    }

    public static Collection<CueSpec> all() {
        init();
        return STYLES.values();
    }

    public static java.util.List<String> ids() {
        init();
        return java.util.List.copyOf(STYLES.keySet());
    }

    public static int size() {
        init();
        return STYLES.size();
    }
}
