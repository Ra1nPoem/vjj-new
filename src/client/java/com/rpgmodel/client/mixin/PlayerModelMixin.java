package com.rpgmodel.client.mixin;

import com.rpgmodel.client.PoseCapture;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抓本地玩家 {@link PlayerModel} 每帧的骨骼姿态（给 rpgmodel 的骨骼调试 HUD 用）。
 *
 * <p>目标：{@code PlayerModel#setupAnim(AvatarRenderState)}（26.3 official 名，无混淆）。
 * 注入在 TAIL：原版 setupAnim 已经 resetPose + 摆完整姿势，此时读取的 xRot/yRot/zRot 就是最终姿态；
 * 只读不写，所以对渲染零副作用。</p>
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void rpgmodel$capturePose(AvatarRenderState state, CallbackInfo callbackInfo) {
        PoseCapture.capture(state, (PlayerModel) (Object) this);
    }
}
