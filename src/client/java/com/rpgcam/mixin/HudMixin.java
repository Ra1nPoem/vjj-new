package com.rpgcam.mixin;

import com.rpgcam.client.CameraDirector;
import com.rpgcam.client.CameraHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 过场 HUD：
 * <ol>
 *   <li>在 {@code Hud.extractRenderState} 头部（原版还没把 isHidden 写进 GuiRenderState 之前）
 *       按样式临时隐藏 HUD —— 26.3 里 {@code GuiRenderState.isHudHidden} 同时会让第一人称手/准星消失，
 *       正好是过场需要的效果；结束/退出时恢复用户原来的 F1 状态。</li>
 *   <li>此时画上下黑边/标题/进度条/黑场；即使原版 HUD 被隐藏，这个 overlay 仍会画出来。</li>
 * </ol>
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Shadow
    private boolean isHidden;

    @Unique
    private boolean rpgcam$suppressed;

    @Unique
    private boolean rpgcam$savedHidden;

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"))
    private void rpgcam$cinematicHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        CameraDirector director = CameraDirector.get();
        boolean wantHidden = director.isActive()
                && director.activeSpec() != null
                && director.activeSpec().hideHud();

        if (wantHidden) {
            if (!rpgcam$suppressed) {
                rpgcam$savedHidden = this.isHidden;
                rpgcam$suppressed = true;
            }
            this.isHidden = true;
        } else if (rpgcam$suppressed) {
            this.isHidden = rpgcam$savedHidden;
            rpgcam$suppressed = false;
        }

        if (director.isActive()) {
            CameraHud.render(graphics, director, deltaTracker);
        }
    }
}
