package com.rpgmodel.api;

import java.util.List;

/**
 * rig 里的一根骨骼。
 *
 * <p>{@code pivot*} 是模型像素坐标（1 像素 = 1/16 方块，相对父骨骼 pivot）；
 * {@code rot*} 是静止姿态的欧拉角，单位<b>度</b>。</p>
 *
 * @param name     骨骼名（humanoid rig 与 {@code PlayerModel} 网格名一致，例如 {@code right_arm}）
 * @param parent   父骨骼名；顶层骨骼为 {@code null}
 * @param pivotX   静止姿态 pivot X（模型像素）
 * @param pivotY   静止姿态 pivot Y（模型像素）
 * @param pivotZ   静止姿态 pivot Z（模型像素）
 * @param rotX     静止姿态绕 X 轴旋转（度）
 * @param rotY     静止姿态绕 Y 轴旋转（度）
 * @param rotZ     静止姿态绕 Z 轴旋转（度）
 * @param children 直接子骨骼名（不可变；无子骨骼为空 list）
 */
public record RigBone(String name, String parent,
                      float pivotX, float pivotY, float pivotZ,
                      float rotX, float rotY, float rotZ,
                      List<String> children) {

    public RigBone {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
