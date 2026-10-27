package com.vjj.client.camera;

import com.vjj.client.VjjUiClient;
import com.vjj.net.VjjPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;

/**
 * 客户端行动锁：服务端 {@link VjjPayloads.LockPayload} 到达后锁住本地输入
 * （移动/跳跃/攻击/使用/破坏），配合服务端位置校正实现“锁死玩家行动”。<b>camera-dev 实现。</b>
 *
 * <p>实现（26.3 已 javap 确认）：</p>
 * <ul>
 *   <li><b>移动/跳跃</b>：锁定期间把 {@code LocalPlayer.input} 换成一个原始 {@link ClientInput}
 *       （其 {@code tick()} 是空实现、{@code moveVector}=ZERO、{@code keyPresses}=EMPTY）。
 *       {@code LocalPlayer.applyInput()} 每 tick 从 input 读取移动向量与跳跃位，所以换掉它等价于
 *       “所有移动键/跳跃键松开”，且不破坏原版其它逻辑；解锁时把原来的 {@code KeyboardInput} 换回。</li>
 *   <li><b>攻击/使用/破坏</b>：{@code com.vjj.mixin.camera.MinecraftInputLockMixin} 在
 *       {@code Minecraft.handleKeybinds} 头部取消（该方法里才有 startAttack/startUseItem/continueAttack）。</li>
 * </ul>
 *
 * <p>日志契约（E2E grep）：</p>
 * <pre>
 * [VjjNew] lock-receive locked=&lt;b&gt; reason=&lt;...&gt;
 * [VjjNew] lock-client locked=&lt;b&gt; reason=&lt;...&gt;
 * </pre>
 */
public final class VjjInputLock {
    private static boolean locked;
    private static String reason = "";
    /** 被替换下来的原输入（解锁时换回）。 */
    private static ClientInput savedInput;
    private static LocalPlayer swappedPlayer;
    private static boolean appliedLogged;

    private VjjInputLock() {
    }

    /** 服务端下发锁/解锁。 */
    public static void onLock(VjjPayloads.LockPayload payload) {
        if (payload == null) {
            return;
        }
        VjjUiClient.LOGGER.info("[VjjNew] lock-receive locked={} reason={}", payload.locked(), payload.reason());
        locked = payload.locked();
        reason = payload.reason() == null ? "" : payload.reason();
        appliedLogged = false;
        VjjUiClient.LOGGER.info("[VjjNew] lock-client locked={} reason={}", locked, reason);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (locked) {
            applyLock(minecraft);
        } else {
            restoreInput();
        }
    }

    /** 每客户端 tick：保证锁定状态与本地输入对象一致（掉线/重进/重生后也会重新接管）。 */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (locked) {
            applyLock(minecraft);
        } else {
            restoreInput();
        }
    }

    public static void reset() {
        if (locked) {
            VjjUiClient.LOGGER.info("[VjjNew] lock-client locked=false reason=reset");
        }
        locked = false;
        reason = "";
        appliedLogged = false;
        restoreInput();
    }

    public static boolean isLocked() {
        return locked;
    }

    public static String reason() {
        return reason;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 输入替换
    // ─────────────────────────────────────────────────────────────────────

    private static void applyLock(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        if (swappedPlayer == player) {
            return;
        }
        restoreInput();
        savedInput = player.input;
        try {
            player.input = new ClientInput();
            // 明确清空当前按键位（原 KeyboardInput 上一帧可能还留着前进/跳跃）
            player.input.keyPresses = Input.EMPTY;
            player.setShiftKeyDown(false);
            player.setSprinting(false);
            player.setDeltaMovement(0.0, 0.0, 0.0);
        } catch (Throwable throwable) {
            VjjUiClient.LOGGER.warn("[VjjNew] lock-client apply failed: {}", throwable.toString());
            return;
        }
        swappedPlayer = player;
        if (!appliedLogged) {
            appliedLogged = true;
            VjjUiClient.LOGGER.info("[VjjNew] lock-client applied locked=true player={} reason={}",
                    player.getGameProfile().name(), reason);
        }
    }

    private static void restoreInput() {
        if (swappedPlayer != null && savedInput != null) {
            try {
                if (swappedPlayer.input != savedInput) {
                    swappedPlayer.input = savedInput;
                }
            } catch (Throwable throwable) {
                VjjUiClient.LOGGER.warn("[VjjNew] lock-client restore failed: {}", throwable.toString());
            }
        }
        swappedPlayer = null;
        savedInput = null;
    }
}
