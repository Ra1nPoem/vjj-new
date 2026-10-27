package com.vjj.mixin.camera;

import com.vjj.client.camera.VjjInputLock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 锁死行动（客户端）：锁定时取消 {@code Minecraft.handleKeybinds}，让攻击/使用/破坏/物品栏等
 * 鼠标键动作全部失效；移动与跳跃由 {@link VjjInputLock} 替换 {@code LocalPlayer.input} 实现。
 *
 * <p>26.3 事实（javap 确认）：{@code startAttack/startUseItem/continueAttack} 全部只在
 * {@code Minecraft.handleKeybinds()} 里被调用，所以一个 HEAD 取消就能覆盖鼠标全部战斗输入。</p>
 *
 * <p>与服务端的“每 tick 位置校正”配合：即使客户端输入没被拦住（例如别的客户端），服务端也会
 * 把玩家拉回锁定位姿。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInputLockMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void vjj$lockKeybinds(CallbackInfo callbackInfo) {
        if (VjjInputLock.isLocked()) {
            callbackInfo.cancel();
        }
    }
}
