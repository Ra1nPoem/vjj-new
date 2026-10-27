package com.vjj.compat.mixin;

import com.vjj.compat.EmfLatePose;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EMF 环境下的「末层 VJJ 姿态」注入（1.1.0）。
 *
 * <p>EFM 在 {@code LivingEntityRenderer#submit} 里、{@code EntityModel#setupAnim} 调用之后才应用资源包动画
 * （见 EMF 的 {@code MixinLivingEntityRenderer#falseAnimation}）。这里在**同一个注入点**、
 * 用比 EMF 更低的 mixin priority（EFM 默认 1000，这里 900 → 后执行）把 VJJ 动作叠上去，
 * 保证 VJJ 的在播动作在 EMF / Touch'n Motion 姿态之上可见。</p>
 *
 * <p>只对 EMF 动画模型生效（{@code EmfLatePose} 内部再次判定），非 EMF 环境零副作用。</p>
 */
@Mixin(value = LivingEntityRenderer.class, priority = 900)
public abstract class LivingEntityRendererEmfPoseMixin {
    @Shadow
    protected EntityModel<?> model;

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V",
                    shift = At.Shift.AFTER))
    private void vjj$applyLateEmfPose(LivingEntityRenderState state, PoseStack poseStack,
                                      SubmitNodeCollector collector, CameraRenderState cameraRenderState,
                                      CallbackInfo callbackInfo) {
        EmfLatePose.apply(state, this.model);
    }
}
