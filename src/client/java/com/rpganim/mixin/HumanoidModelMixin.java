package com.rpganim.mixin;

import com.rpganim.client.ClientAnimState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 压低原版挥臂：战斗/受击类动作播放期间，取消 {@code setupAttackAnimation} 摆出的挥臂姿势，
 * 否则原版挥臂会和 content.json 里声明的战斗动作叠加成一团乱姿势。
 * <p>只对玩家实体（{@link AvatarRenderState}）生效；命中动作由
 * {@code AnimDefinition#suppressesVanillaAttack()} 判定（combat / hit 分类）。
 * 其他 humanoid（僵尸、骷髅、盔甲架…）完全不受影响。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Inject(
            method = "setupAttackAnimation(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rpganim$suppressVanillaAttack(HumanoidRenderState state, CallbackInfo ci) {
        if (state instanceof AvatarRenderState avatar
                && ClientAnimState.suppressesVanillaAttack(avatar.id)) {
            ci.cancel();
        }
    }
}
