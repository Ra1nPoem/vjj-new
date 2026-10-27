package com.vjj.compat;

import punchy.client.animation.PunchyThirdPersonAnimationManager;
import punchy.config.PunchyConfig;

/**
 * Punchy 兼容桥的**实现类**（唯一允许引用 Punchy 类型的类）。
 *
 * <p>只在 {@link PunchyBridge} 确认 {@code punchy} 已加载后才会被类加载；
 * Punchy 未装时本类所有引用都不会触发，VJJ 仍可正常启动。</p>
 *
 * <p>用到的 Punchy 公开 API（2.8g，MC 26.3）：</p>
 * <ul>
 *   <li>{@link PunchyConfig#isModEnabled()} — 玩家配置总开关；</li>
 *   <li>{@link PunchyThirdPersonAnimationManager#playManual(String)} — 第三人称手动动画（假人/被观察玩家）。</li>
 * </ul>
 */
final class PunchyBridgeImpl {
    private PunchyBridgeImpl() {
    }

    /** Punchy 的 client 初始化由它自己的 entrypoint 完成；这里只做一次轻量探活。 */
    static void init() {
        // 触发一次配置读取（Punchy 的 AutoConfig holder 在它自己初始化时已加载；读一次不会写盘）
        PunchyConfig.isModEnabled();
    }

    static boolean isEnabled() {
        return PunchyConfig.isModEnabled();
    }

    static boolean playThirdPerson(String clip) {
        PunchyThirdPersonAnimationManager.playManual(clip);
        return true;
    }
}
