package com.rpgmodel.api;

import java.util.List;
import java.util.StringJoiner;

/**
 * 骨骼名校验报告（{@link RpgModelApi#validateBones(String, java.util.Collection)} 的返回值）。
 *
 * @param checked      本次校验的骨骼名总数
 * @param knownBones   属于该 rig 的骨骼名（顺序与输入顺序一致）
 * @param unknownBones 不属于该 rig 的骨骼名（顺序与输入顺序一致）
 */
public record ValidationReport(int checked, List<String> knownBones, List<String> unknownBones) {

    public ValidationReport {
        knownBones = knownBones == null ? List.of() : List.copyOf(knownBones);
        unknownBones = unknownBones == null ? List.of() : List.copyOf(unknownBones);
    }

    /** @return 是否全部合法（{@code unknownBones} 为空） */
    public boolean ok() {
        return unknownBones.isEmpty();
    }

    /** @return 单行摘要，形如 {@code checked=12 known=12 unknown=0 [unknown names...]} */
    public String summary() {
        StringJoiner names = new StringJoiner(",");
        for (String bone : unknownBones) {
            names.add(bone);
        }
        return "checked=" + checked
                + " known=" + knownBones.size()
                + " unknown=" + unknownBones.size()
                + " [" + names + "]";
    }
}
