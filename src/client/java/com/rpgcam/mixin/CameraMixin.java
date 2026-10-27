package com.rpgcam.mixin;

import com.rpgcam.client.CameraDirector;
import com.vjj.client.camera.VjjZoomClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 把相机姿态收敛到 {@link Camera#update(DeltaTracker)}（算出 fov → 剔除视锥 → 投影），
 * 所以在这里的 TAIL 接管：先让原版算完，再覆写位置/朝向，并用新朝向重算剔除视锥
 * （否则玩家视角的旧视锥会把镜头里的区块剔掉，截图就会出现空洞）。
 *
 * <p>FOV 在 {@link Camera#calculateFov} 的返回值上改——它发生在投影矩阵生成之前，
 * 所以投影/剔除/最终画面三者一致。</p>
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    public abstract Matrix4f getViewRotationMatrix(Matrix4f destination);

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void rpgcam$applyCinematicPose(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraDirector director = CameraDirector.get();
        if (!director.isActive()) {
            return;
        }
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        CameraDirector.PoseData pose = director.poseFor(partialTick);
        if (pose == null) {
            return;
        }
        setPosition(pose.pos());
        setRotation(pose.yaw(), pose.pitch());

        // 用新朝向重算剔除视锥（私方法走 CameraInvoker）
        CameraInvoker invoker = (CameraInvoker) (Object) this;
        Matrix4f viewRotation = new Matrix4f();
        getViewRotationMatrix(viewRotation);
        invoker.rpgcam$prepareCullFrustum(viewRotation,
                invoker.rpgcam$createProjectionMatrixForCulling(), pose.pos());

        director.onPoseApplied(pose);
    }

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
    private void rpgcam$modifyFov(float partialTick, CallbackInfoReturnable<Float> callbackInfo) {
        float vanilla = callbackInfo.getReturnValueF();
        // 旧 13 样式 / 关键帧镜头的 FOV 优先，再叠客户端变焦（VJJ-NEW：按住变焦键 / 服务端强制变焦）
        float modified = CameraDirector.get().modifyFov(vanilla, partialTick);
        float zoomed = VjjZoomClient.modifyFov(modified);
        if (zoomed != vanilla) {
            callbackInfo.setReturnValue(zoomed);
        }
    }
}
