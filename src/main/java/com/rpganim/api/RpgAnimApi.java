package com.rpganim.api;

import com.rpganim.RpgAnim;
import com.rpganim.cutscene.CutsceneManager;
import com.rpganim.server.AnimServerManager;
import com.rpganim.server.ContentStore;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 导出给其它 mod 的服务端动画 API（薄 api jar：{@code dist/rpganim-1.0.0-api.jar}，
 * 其它 mod 用 {@code compileOnly} 依赖它，运行期由 rpganim mod 提供实现）。
 *
 * <p>调用方约定：使用前用 {@code FabricLoader.getInstance().isModLoaded("rpganim")} 守卫，
 * 并把调用隔离在单独的小类里（JVM 惰性链接，未安装 rpganim 时不会触发类加载失败）。</p>
 *
 * <p>本类只依赖服务端类，客户端不需要它。</p>
 */
public final class RpgAnimApi {
    public static final String MOD_ID = "rpganim";
    public static final String VERSION = RpgAnim.VERSION;

    private RpgAnimApi() {
    }

    /** 本 mod 版本（"1.0.0"）。 */
    public static String version() {
        return RpgAnim.VERSION;
    }

    /** 用动作默认时长播放（循环动作一直播到被停止/替换）。 */
    public static boolean play(ServerPlayer target, String animId) {
        return play(target, animId, -1, "api");
    }

    /**
     * 播放一个动作。
     *
     * @param durationTicks {@code <=0} 用动作默认时长
     * @param source        来源标记（写进 anim-start 日志的 source 字段；空则用 "api"）
     * @return 是否成功开始播放
     */
    public static boolean play(ServerPlayer target, String animId, int durationTicks, String source) {
        String effectiveSource = source == null || source.isBlank() ? "api" : source;
        return AnimServerManager.get().play(target, animId, durationTicks, effectiveSource) > 0;
    }

    /** 停止该玩家当前动作。 */
    public static boolean stop(ServerPlayer target) {
        return AnimServerManager.get().stop(target, "stop");
    }

    /** 当前是否有动作在播。 */
    public static boolean isPlaying(ServerPlayer target) {
        return AnimServerManager.get().isPlaying(target);
    }

    /** 全部动作 id（有序）。 */
    public static List<String> animationIds() {
        return ContentStore.get().animationIds();
    }

    /** 全部过场 id（有序）。 */
    public static List<String> cutsceneIds() {
        return ContentStore.get().cutsceneIds();
    }

    /** 给玩家播放一段过场（镜头 + 动作时间轴；没装 rpgcam 时自动降级为无镜头）。 */
    public static boolean playCutscene(ServerPlayer target, String cutsceneId) {
        return CutsceneManager.get().play(target, cutsceneId) > 0;
    }
}
