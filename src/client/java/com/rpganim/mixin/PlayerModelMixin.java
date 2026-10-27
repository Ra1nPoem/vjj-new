package com.rpganim.mixin;

import com.rpganim.client.ClientAnimState;
import com.vjj.compat.EmfBridge;
import com.vjj.compat.VjjCompat;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 rpganim 的本地动作偏移叠加到玩家模型的骨骼上。
 * <p>注入点选 {@code PlayerModel#setupAnim(AvatarRenderState)} 的 {@code TAIL}：
 * 该方法先按皮肤开关设置 sleeves/pants 可见性，再调用
 * {@code HumanoidModel#setupAnim} 摆好原版姿势（含挥臂/游泳等），
 * 因此 TAIL 是「干净叠加层」——每帧原版姿势重算后我们再加偏移，
 * 不播放动作时自然回到原版，不会残留。
 * <p>目标是 26.3 的 official 名（无混淆/无 refmap），descriptor 必须写全；
 * PlayerModel 里还有 {@code setupAnim(HumanoidRenderState)} / {@code setupAnim(Object)}
 * 两个桥方法，靠完整 descriptor 精确命中 {@code AvatarRenderState} 重载。
 *
 * <p><b>1.1.0 EMF 兼容</b>：EMF + Touch'n Motion + Fresh Animations: Player Extension 环境下，
 * EMF 会在 {@code onBipedPoseCopyEnd} 把 biped pose 应用回同一个模型，会覆盖这里叠加的偏移。
 * 所以当 {@link EmfBridge#isModelEmfAnimated(Object)} 为 true（EMF 动画模型 + 桥可用）时，
 * 这里**跳过**叠加，改由 {@code com.vjj.compat.EmfBridgeImpl} 注册的 EMFAnimationHook
 * 在 EMF 应用 pose 之后再叠加（VJJ 动作最后叠加 = 优先可见）。
 * 未装 EMF / 桥关闭 / hook 注册失败时保持 1.0.0 的原生行为。</p>
 *
 * <p>日志：每条管线首次各一条 {@code [VjjNew] anim-lane model=<emf|mixin> entity=<id>}。</p>
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {

    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
            at = @At("TAIL"))
    private void rpganim$applyBoneOffsets(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        if (EmfBridge.isModelEmfAnimated(model)) {
            // EMF 动画模型：交给 EMF hook 管线（pose copy 之后叠加，避免被 EMF 覆盖/重复叠加）
            VjjCompat.logAnimLane("emf", state.id);
            return;
        }
        VjjCompat.logAnimLane("mixin", state.id);
        ClientAnimState.applyToPlayerModel(state.id, model);
    }
}
