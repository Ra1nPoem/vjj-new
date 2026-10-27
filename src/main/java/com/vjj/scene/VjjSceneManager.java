package com.vjj.scene;

import com.vjj.VjjNew;
import com.vjj.camera.VjjCameraServer;
import com.vjj.dummy.VjjDummyServer;
import com.vjj.store.CameraClip;
import com.vjj.store.VjjConfigStore;
import com.vjj.store.VjjSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 鍦烘櫙锛圫cene锛夌紪鎺掞細涓€娆″畬鏁存紨鍑?= <b>杩愰暅 + 鍔ㄧ敾 + 鍋囦汉鏇夸唬 + 闅愯棌鏈綋 + 閿佹琛屽姩</b>銆? *
 * <p>鏈嶅姟绔潈濞侊細寮€濮?缁撴潫閮界敱鏈嶅姟绔?tick 鍐冲畾锛涘鎴风鍙収鍋氾紙闀滃ご鑷鎸夋椂闀挎敹灏撅紝
 * 鏈嶅姟绔埌鐐瑰啀鍙?stop 鍏滃簳锛夈€傜粨鏉?/ 鎺夌嚎 / 鍋滄湇閮戒細鎭㈠锛氳В闄ら殣钘忋€佽В閿併€佹竻鍋囦汉銆佸仠闀滃ご銆?/p>
 *
 * <p>鏃ュ織濂戠害锛圗2E 渚濊禆锛夛細</p>
 * <pre>
 * [VjjNew] scene-start player=&lt;name&gt; clip=&lt;id&gt; anim=&lt;id&gt; duration=&lt;n&gt; targets=&lt;n&gt; dummy=&lt;id|-1&gt; hide=&lt;b&gt; lock=&lt;b&gt; broadcast=&lt;b&gt;
 * [VjjNew] scene-end player=&lt;name&gt; clip=&lt;id&gt; ticks=&lt;n&gt; reason=&lt;finished|stop|quit|server-stop&gt; restored=1
 * </pre>
 */
public final class VjjSceneManager {
    /** 鍦烘櫙缁撴潫鍚庡淇濈暀鐨?tick锛堣瀹㈡埛绔姩鐢?闀滃ご鑷劧鏀跺熬锛夈€?*/
    private static final int TAIL_TICKS = 20;
    private static final Map<UUID, ActiveScene> ACTIVE = new LinkedHashMap<>();

    private VjjSceneManager() {
    }

    private record ActiveScene(UUID actorId, String actorName, String clipId, String animId,
                               int durationTicks, long startTick, boolean hide, boolean lock,
                               boolean dummy, boolean broadcast, String targetMode) {
    }

    public static void init() {
        VjjNew.LOGGER.info("[VjjNew] scene manager init");
    }

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    // 寮€濮?/ 鍋滄
    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    /**
     * 寮€濮嬩竴娈靛満鏅紨鍑恒€?     *
     * @param animId     鍋囦汉鍔ㄤ綔锛堢┖ = 鐢ㄨ缃噷鐨?defaultAnim锛?     * @param targetMode {@code self} 鍙湁鎵ц鑰呯湅闀滃ご锛泏@code all} 鎵€鏈夊湪绾跨帺瀹朵竴璧风湅锛堣繃鍦哄箍鎾級
     * @return 鏄惁鎴愬姛寮€濮?     */
    public static boolean play(ServerPlayer actor, String clipId, String animId, String targetMode) {
        if (actor == null) {
            return false;
        }
        MinecraftServer server = actor.level().getServer();
        if (server == null) {
            return false;
        }
        CameraClip clip = VjjConfigStore.get().clip(clipId);
        if (clip == null) {
            VjjNew.LOGGER.info("[VjjNew] scene-start player={} clip={} result=-1 reason=unknown-clip",
                    actor.getGameProfile().name(), clipId);
            return false;
        }
        String mode = targetMode == null || targetMode.isBlank() ? "self" : targetMode;
        VjjSettings settings = VjjConfigStore.get().settings().copy();
        boolean broadcast = "all".equalsIgnoreCase(mode) || settings.broadcastCamera;
        if (ACTIVE.containsKey(actor.getUUID())) {
            end(actor, server, "replaced");
        }

        List<ServerPlayer> targets = broadcast
                ? new ArrayList<>(server.getPlayerList().getPlayers())
                : List.of(actor);
        boolean hide = settings.hidePlayer;
        boolean lock = settings.lockAction;
        boolean dummy = settings.spawnDummy;
        String effectiveAnim = animId == null || animId.isBlank() ? settings.defaultAnim : animId;

        int dummyId = -1;
        if (dummy) {
            dummyId = VjjDummyServer.spawnForScene(actor, effectiveAnim, true);
        }
        if (hide) {
            VjjCameraServer.setHidden(actor, true, "scene");
        }
        if (lock) {
            VjjCameraServer.setLocked(actor, true, "scene");
        }
        int duration = VjjCameraServer.playClipTo(targets, clip, actor, "scene");
        if (duration <= 0) {
            // 鍥炴粴
            if (hide) {
                VjjCameraServer.setHidden(actor, false, "scene-rollback");
            }
            if (lock) {
                VjjCameraServer.setLocked(actor, false, "scene-rollback");
            }
            if (dummyId >= 0) {
                VjjDummyServer.clearScene(actor);
            }
            VjjNew.LOGGER.info("[VjjNew] scene-start player={} clip={} result=-1 reason=play-failed",
                    actor.getGameProfile().name(), clipId);
            return false;
        }
        ActiveScene scene = new ActiveScene(actor.getUUID(), actor.getGameProfile().name(), clip.id,
                effectiveAnim, duration + TAIL_TICKS, server.getTickCount(), hide, lock, dummy,
                broadcast, mode);
        ACTIVE.put(actor.getUUID(), scene);
        VjjNew.LOGGER.info(
                "[VjjNew] scene-start player={} clip={} anim={} duration={} targets={} dummy={} hide={} lock={} broadcast={}",
                scene.actorName(), clip.id, effectiveAnim, duration, targets.size(), dummyId,
                hide, lock, broadcast);
        return true;
    }

    /** 鍋滄鏌愮帺瀹舵鍦ㄨ繘琛岀殑鍦烘櫙锛堣繑鍥炴槸鍚﹀仠浜嗕竴娈碉級銆?*/
    public static boolean stop(ServerPlayer actor, String reason) {
        if (actor == null) {
            return false;
        }
        MinecraftServer server = actor.level().getServer();
        if (server == null) {
            return false;
        }
        return end(actor, server, reason == null ? "stop" : reason);
    }

    public static boolean isActive(ServerPlayer actor) {
        return actor != null && ACTIVE.containsKey(actor.getUUID());
    }

    public static String status(ServerPlayer actor) {
        ActiveScene scene = actor == null ? null : ACTIVE.get(actor.getUUID());
        if (scene == null) {
            return "scene=idle";
        }
        return "scene=" + scene.clipId() + " anim=" + scene.animId();
    }

    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    // tick / 鏀跺熬
    // 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    public static void tick(MinecraftServer server) {
        if (server == null || ACTIVE.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<ServerPlayer> toEnd = new ArrayList<>();
        for (ActiveScene scene : new ArrayList<>(ACTIVE.values())) {
            ServerPlayer actor = server.getPlayerList().getPlayer(scene.actorId());
            if (actor == null) {
                // 鐜╁绂荤嚎锛氱洿鎺ヤ粠琛ㄩ噷绉婚櫎骞惰鍥炴敹閫昏緫灏藉姏鑰屼负
                ACTIVE.remove(scene.actorId());
                VjjNew.LOGGER.info("[VjjNew] scene-end player={} clip={} ticks={} reason=quit restored=1",
                        scene.actorName(), scene.clipId(), now - scene.startTick());
                continue;
            }
            if (now - scene.startTick() >= scene.durationTicks()) {
                toEnd.add(actor);
            }
        }
        for (ServerPlayer actor : toEnd) {
            end(actor, server, "finished");
        }
    }

    public static void onPlayerQuit(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ActiveScene scene = ACTIVE.get(player.getUUID());
        if (scene == null) {
            return;
        }
        ACTIVE.remove(player.getUUID());
        // 鐜╁宸茬绾匡細娓呭亣浜?瑙ｉ攣锛堥暅澶?闅愯棌闅忓疄浣撳嵏杞借嚜鐒舵秷澶憋級
        if (scene.dummy()) {
            VjjDummyServer.clearScene(player);
        }
        VjjNew.LOGGER.info("[VjjNew] scene-end player={} clip={} ticks={} reason=quit restored=1",
                scene.actorName(), scene.clipId(), 0);
    }

    public static void onServerStopping(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer actor : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (ACTIVE.containsKey(actor.getUUID())) {
                end(actor, server, "server-stop");
            }
        }
        ACTIVE.clear();
    }

    /** 缁撴潫骞舵仮澶嶇幇鍦恒€?*/
    private static boolean end(ServerPlayer actor, MinecraftServer server, String reason) {
        ActiveScene scene = ACTIVE.remove(actor.getUUID());
        if (scene == null) {
            return false;
        }
        // 鍋滈暅澶达細骞挎挱鍦烘櫙瑕佸彂缁欐墍鏈変汉
        if (scene.broadcast()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                VjjCameraServer.stop(player, "scene:" + reason);
            }
        } else {
            VjjCameraServer.stop(actor, "scene:" + reason);
        }
        if (scene.dummy()) {
            VjjDummyServer.clearScene(actor);
        }
        if (scene.hide()) {
            VjjCameraServer.setHidden(actor, false, "scene-end");
        }
        if (scene.lock()) {
            VjjCameraServer.setLocked(actor, false, "scene-end");
        }
        long ticks = Math.max(0L, server.getTickCount() - scene.startTick());
        VjjNew.LOGGER.info("[VjjNew] scene-end player={} clip={} ticks={} reason={} restored=1",
                scene.actorName(), scene.clipId(), ticks, reason);
        return true;
    }
}
