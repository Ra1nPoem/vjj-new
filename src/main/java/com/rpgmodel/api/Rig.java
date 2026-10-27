package com.rpgmodel.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个骨骼模型（rig）的只读定义。
 *
 * @param id            rig 唯一 id（例如 {@code humanoid}）
 * @param nameKey       显示名翻译键（例如 {@code rig.rpgmodel.humanoid}）
 * @param rootBone      根骨骼名（{@code bones} 里 {@code parent == null} 的那根；可显式指定）
 * @param boneNames     骨骼名列表（有序、不可变）
 * @param bones         骨骼名 → {@link RigBone}（有序、不可变）
 * @param attachPoints  挂点列表（不可变；无挂点为空 list）
 */
public record Rig(String id, String nameKey, String rootBone,
                  List<String> boneNames,
                  Map<String, RigBone> bones,
                  List<AttachPoint> attachPoints) {

    public Rig {
        boneNames = boneNames == null ? List.of() : List.copyOf(boneNames);
        bones = bones == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        attachPoints = attachPoints == null ? List.of() : List.copyOf(attachPoints);
    }

    /** @return 指定骨骼；不存在返回 {@code null} */
    public RigBone bone(String name) {
        return name == null ? null : bones.get(name);
    }

    /** @return 顶层骨骼（{@code parent == null}），按 {@link #boneNames()} 的顺序 */
    public List<RigBone> rootBones() {
        List<RigBone> roots = new ArrayList<>(bones.size());
        for (String name : boneNames) {
            RigBone bone = bones.get(name);
            if (bone != null && bone.parent() == null) {
                roots.add(bone);
            }
        }
        return List.copyOf(roots);
    }
}
