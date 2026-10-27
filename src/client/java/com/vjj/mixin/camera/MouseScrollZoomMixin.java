package com.vjj.mixin.camera;

import com.vjj.client.camera.VjjZoomClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 变焦滚轮：按住变焦键（默认 Z）时用滚轮调倍率。
 *
 * <p>26.3 的 {@code MouseHandler.onScroll(long window, double xOffset, double yOffset)} 是鼠标滚轮的唯一入口
 * （{@code ScrollWheelHandler} 是 private 字段没有 getter，所以用 mixin 最稳）；只在“正在变焦 + 没有打开界面”
 * 时消费滚动，其余滚动物品栏/界面照常。</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollZoomMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void vjj$zoomScroll(long window, double xOffset, double yOffset, CallbackInfo callbackInfo) {
        if (VjjZoomClient.handleScroll(yOffset)) {
            callbackInfo.cancel();
        }
    }
}
