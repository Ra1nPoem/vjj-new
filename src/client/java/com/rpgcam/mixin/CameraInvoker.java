package com.rpgcam.mixin;

import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link Camera} 私有方法的调用器（26.3 的剔除视锥重建需要）。
 */
@Mixin(Camera.class)
public interface CameraInvoker {
    @Invoker("createProjectionMatrixForCulling")
    Matrix4f rpgcam$createProjectionMatrixForCulling();

    @Invoker("prepareCullFrustum")
    void rpgcam$prepareCullFrustum(Matrix4fc viewRotationProjection, Matrix4f projection, Vec3 position);
}
