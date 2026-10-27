package com.rpganim.interop;

import com.rpgcam.api.RpgCameraApi;
import net.minecraft.server.level.ServerPlayer;

/**
 * 全工程**唯一**引用 {@code com.rpgcam.api.*} 的类。
 *
 * <p>它只应在确认 rpgcam 已加载（{@code FabricLoader.isModLoaded("rpgcam")}）之后由
 * {@link RpgAnimInterop} 间接调用：JVM 的方法是惰性链接的，未安装 rpgcam 的实例
 * 永远不会解析本类的常量池，因此 {@link RpgCameraApi} 不会被加载，也就不会出现
 * {@code NoClassDefFoundError}。反过来，任何其它类都不允许直接 import rpgcam 的类，
 * 否则 soft dependency 就失效了（构建期的静态审计会检查这一点，见 docs/contract.md）。</p>
 *
 * <p>本类不捕获异常：调用方 {@link RpgAnimInterop} 统一 try/catch(Throwable)，
 * 保证镜头联动失败不影响过场时间轴。</p>
 */
final class RpgCameraHook {
    private RpgCameraHook() {
    }

    /**
     * 给玩家播放一段 rpgcam 运镜。
     *
     * @param player        目标玩家
     * @param styleId       rpgcam 样式 id（boss_intro / victory / orbit）
     * @param durationTicks {@code <=0} 使用样式默认时长
     * @param label         镜头 overlay 上显示的附加文字（过场标题键）
     * @return 实际时长（tick）；失败返回 -1（对端没装 rpgcam 客户端 / 玩家离线 / 样式不存在）
     */
    static int play(ServerPlayer player, String styleId, int durationTicks, String label) {
        return RpgCameraApi.play(player, styleId, durationTicks, label);
    }

    /** 立刻结束该玩家当前的运镜。 */
    static boolean stop(ServerPlayer player) {
        return RpgCameraApi.stop(player);
    }
}
