package com.vjj.client.camera;

import com.rpgcam.client.CameraDirector;
import com.vjj.net.VjjPayloads;
import net.minecraft.client.Minecraft;

/**
 * 客户端运镜模块门面（相机接管 / 关键帧镜头播放 / 锁行动输入锁）。
 *
 * <p><b>Lead 定契约、camera-dev 实现</b>：方法签名固定。详见 docs/VJJ-DESIGN.md §3.1。</p>
 *
 * <p>关键帧镜头的演算本体在 {@link CameraDirector}（与旧 13 样式 cue 并存，优先级 clip &gt; cue），
 * 这里只做 S2C 入口的拆包/日志/状态查询，便于网络层与其它模块调用。</p>
 */
public final class VjjCameraClient {
    private VjjCameraClient() {
    }

    /** 服务端下发“播放可编辑镜头”。 */
    public static void onClipPlay(VjjPayloads.ClipPlayPayload payload) {
        if (payload == null) {
            return;
        }
        com.vjj.client.VjjUiClient.LOGGER.info(
                "[VjjNew] clip-receive source={} duration={} force={} lock={} hide={} anchor={}",
                payload.source(), payload.durationTicks(), payload.forceCamera(),
                payload.lockAction(), payload.hidePlayer(), payload.anchorEntityId());
        CameraDirector.get().receiveClip(payload);
    }

    /** 服务端下发停止（兜底）。 */
    public static void onClipStop(VjjPayloads.ClipStopPayload payload) {
        String reason = payload == null || payload.reason() == null || payload.reason().isBlank()
                ? "stop" : payload.reason();
        com.vjj.client.VjjUiClient.LOGGER.info("[VjjNew] clip-stop-receive reason={}", reason);
        CameraDirector.get().receiveClipStop(reason);
    }

    /** 每个客户端 tick：收尾逻辑在 CameraDirector.onClientTick（由 rpgcam 客户端入口驱动），这里只做兜底检查。 */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        CameraDirector director = CameraDirector.get();
        if (director.isClipActive() && (minecraft.player == null || minecraft.level == null)) {
            director.resetClip();
        }
    }

    /** 断线/重置：清空关键帧镜头活动态（相机类型由 CameraDirector 的读秒兜底恢复）。 */
    public static void reset() {
        CameraDirector.get().resetClip();
    }

    public static boolean isActive() {
        return CameraDirector.get().isClipActive();
    }

    /** 当前关键帧镜头 id（无则空串）。 */
    public static String activeClipId() {
        String clipId = CameraDirector.get().activeClipId();
        return clipId == null ? "" : clipId;
    }

    /** 进度 0..1（界面显示用）。 */
    public static float progress() {
        return CameraDirector.get().clipProgress();
    }

    /** “elapsed/duration”文本（界面显示用）。 */
    public static String clipTicksText() {
        CameraDirector director = CameraDirector.get();
        return director.clipElapsedTicks() + "/" + director.clipDurationTicks() + "t";
    }

    /** 诊断状态行（服务端 /status 或日志取证用）。 */
    public static String status() {
        CameraDirector director = CameraDirector.get();
        if (!director.isClipActive()) {
            return "clip=idle";
        }
        return "clip=" + director.activeClipId()
                + " t=" + String.format(java.util.Locale.ROOT, "%.2f", director.clipProgress())
                + " elapsed=" + director.clipElapsedTicks()
                + " duration=" + director.clipDurationTicks()
                + " keys=" + director.clipKeyCount()
                + " force=" + director.isClipForcingCamera();
    }
}
