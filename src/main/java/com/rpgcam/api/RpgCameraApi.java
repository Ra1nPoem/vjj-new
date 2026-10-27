package com.rpgcam.api;

import com.rpgcam.cue.CueRegistry;
import com.rpgcam.cue.CueSpec;
import com.rpgcam.server.CameraCueManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 给其它 mod 用的服务端镜头 API（例如 QuestGuide 在任务完成时触发运镜）。
 *
 * <p>调用方必须先检查 {@code FabricLoader.getInstance().isModLoaded("rpgcam")}，
 * 并且把对 API 的调用隔离在单独的类里，避免未安装本 mod 时加载到该类。</p>
 *
 * <p>本类只依赖服务端类，客户端不需要它。</p>
 */
public final class RpgCameraApi {
    public static final String MOD_ID = "rpgcam";
    public static final String VERSION = "1.0.0";

    private RpgCameraApi() {
    }

    /** 本 mod 是否可用（被调用即说明已加载；保留给防御性检查）。 */
    public static boolean isAvailable() {
        return true;
    }

    /** 全部样式 id（有序）。 */
    public static List<String> styleIds() {
        return CueRegistry.ids();
    }

    public static boolean hasStyle(String styleId) {
        return CueRegistry.has(styleId);
    }

    /** 样式默认时长（tick）；未知样式返回 -1。 */
    public static int defaultDuration(String styleId) {
        CueSpec spec = CueRegistry.get(styleId);
        return spec == null ? -1 : spec.durationTicks();
    }

    /**
     * 给玩家播放一段运镜（默认时长、无附加文字）。
     *
     * @return 实际时长（tick）；失败返回 -1（对端没装客户端、样式不存在、玩家不在线）
     */
    public static int play(ServerPlayer player, String styleId) {
        return CameraCueManager.get().playFromApi(player, styleId, -1, null, null, "");
    }

    /**
     * 给玩家播放一段运镜。
     *
     * @param durationTicks &lt;=0 表示用样式默认时长
     * @param label         镜头 overlay 显示的附加文字（例如任务名），可为空
     * @return 实际时长（tick）；失败返回 -1
     */
    public static int play(ServerPlayer player, String styleId, int durationTicks, String label) {
        return CameraCueManager.get().playFromApi(player, styleId, durationTicks, null, null, label);
    }

    /** 立刻结束该玩家当前的运镜。 */
    public static boolean stop(ServerPlayer player) {
        return CameraCueManager.get().stop(player, "api");
    }

    /** 当前是否有运镜在播。 */
    public static boolean isPlaying(ServerPlayer player) {
        return CameraCueManager.get().isPlaying(player);
    }
}
