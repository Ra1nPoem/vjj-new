package com.rpganim.client;

import com.rpganim.anim.AnimDefinition;
import com.rpganim.anim.AnimSampler;
import com.rpganim.anim.BoneOffsets;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端自测钩子（{@code -Drpganim.autotest=on} 时启用；正常游玩零副作用），
 * 供 E2E 取证（设计文档 §5.5 / §5.8）：
 * <ul>
 *   <li>进世界打 {@code [RpgAnim-AUTOTEST] in-world player=... pos=BlockPos{...}}；</li>
 *   <li>自动切第三人称（E2E 截图必须能看见玩家模型）：动作截图用**正面**（背后视角看不到
 *       「格挡前伸 / 双臂过头」），过场时保持**背后**（rpgcam 镜头）；被切回第一人称时静默还原；</li>
 *   <li>每个新动作 id 在其**幅度峰值**（所有骨骼 rotation 绝对值之和最大的 tick，§7 目检要求
 *       能看出「重击双臂过头」等姿势；内容未同步时退回 50%）截图 {@code rpganim-NN-<id>.png}
 *       （最多 20 张，按 id 去重）；过场在 33% 进度处也截一张 {@code rpganim-NN-cutscene-<id>.png}；</li>
 *   <li>链路自检：每 10 tick 真实攻击 4 格内最近活体（验证服务端 {@code anim-trigger source=attack}）；
 *       约每 200 tick 发一次真实 C2S {@code EmoteRequestPayload}（验证 {@code source=emote-request}）；</li>
 *   <li>截图是「上一帧」语义 + 异步落盘：轮询文件 {@code length()>0} 才打 {@code shot-ok=...}。</li>
 * </ul>
 * 自检行为只在 autotest 开关打开时执行，走的是真实客户端输入/网络路径（不直接调用服务端类），
 * 与玩家实际按键/点击完全等价。
 */
public final class AnimAutotest {
    private static final String PREFIX = "[RpgAnim-AUTOTEST] ";
    private static final int MAX_SHOTS = 20;
    /** 自动攻击间隔（tick）。 */
    private static final int ATTACK_INTERVAL_TICKS = 10;
    /** 自动攻击搜索半径（方块）。 */
    private static final double ATTACK_RADIUS = 4.0;
    /** 自动 C2S emote 请求间隔（tick）。 */
    private static final int EMOTE_REQUEST_INTERVAL_TICKS = 200;
    /** 被动作/过场挡住时的快速重试间隔（tick）。 */
    private static final int EMOTE_REQUEST_RETRY_TICKS = 20;
    /** 进世界后的稳定期：先等区块/模型加载完，避免在「加载地形中」造成动作与废截图。 */
    private static final int WORLD_SETTLE_TICKS = 80;
    /** 自动 C2S 请求的固定动作 id。 */
    private static final String AUTOTEST_EMOTE_ID = "emote_point";

    /** 已排期、等到动作/过场跑到目标进度再截的图。 */
    private static final class Scheduled {
        final String animId; // null = 过场
        final String cutsceneId;
        final int entityId;
        final int targetTicks;
        final String file;

        Scheduled(String animId, String cutsceneId, int entityId, int targetTicks, String file) {
            this.animId = animId;
            this.cutsceneId = cutsceneId;
            this.entityId = entityId;
            this.targetTicks = targetTicks;
            this.file = file;
        }

        boolean isCutscene() {
            return animId == null;
        }
    }

    private static boolean enabled;
    private static boolean inWorldLogged;
    private static boolean thirdPersonForced;
    private static boolean frontCameraLogged;
    private static int sequence;
    private static int worldTicks;
    private static int attackCooldown;
    private static int emoteRequestCooldown;
    private static final Set<String> SEEN_ANIM_IDS = new HashSet<>();
    private static final Set<String> SEEN_CUTSCENE_IDS = new HashSet<>();
    private static final List<Scheduled> SCHEDULED = new ArrayList<>();
    private static final Deque<String> SHOT_QUEUE = new ArrayDeque<>();
    private static String pendingShot;
    private static int pendingWait;

    private AnimAutotest() {
    }

    public static void register() {
        String value = System.getProperty("rpganim.autotest", "off");
        enabled = value != null && !"off".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value);
        if (enabled) {
            log("enabled property=" + value);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 事件（ClientAnimState 在主线程回调）
    // ─────────────────────────────────────────────────────────────────────

    /** 新动作开始：按 id 去重，50% 进度截一张。 */
    public static void onActionStart(ClientAnimState.ActiveAnim active) {
        if (!enabled || active == null || active.animId.isBlank()) {
            return;
        }
        if (sequence >= MAX_SHOTS || !SEEN_ANIM_IDS.add(active.animId)) {
            return;
        }
        sequence++;
        String file = String.format(Locale.ROOT, "rpganim-%02d-%s.png", sequence, sanitize(active.animId));
        int shotTick = peakShotTick(active.def, active.durationTicks);
        SCHEDULED.add(new Scheduled(active.animId, null, active.entityId, shotTick, file));
        log("shot-scheduled file=" + file + " anim=" + active.animId + " at=" + shotTick);
    }

    /**
     * 截图时机 = 动作幅度峰值（§7 目检要能看出「重击双臂过头 / 受击后仰 / 格挡前伸」，
     * 固定 50% 会落在「举过头→下砸」的过渡帧）。对 0..duration 逐 tick 用真实采样器求
     * 所有骨骼 rotation 绝对值之和，取最大者为截图 tick；内容未同步时退回 50%。
     */
    private static int peakShotTick(AnimDefinition def, int durationTicks) {
        int fallback = Math.max(1, durationTicks / 2);
        if (def == null || def.bones().isEmpty()) {
            return fallback;
        }
        int duration = Math.max(1, def.durationTicks());
        int bestTick = fallback;
        float bestScore = -1.0F;
        for (int tick = 0; tick <= duration; tick++) {
            Map<String, BoneOffsets> sampled = AnimSampler.sample(def, tick, 1.0F);
            float score = 0.0F;
            for (BoneOffsets offsets : sampled.values()) {
                if (offsets.hasRotation()) {
                    float[] rotation = offsets.rotationRad();
                    score += Math.abs(rotation[0]) + Math.abs(rotation[1]) + Math.abs(rotation[2]);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestTick = tick;
            }
        }
        // 不要太早截（混合权重还没上来，姿势不足）也不要卡到最后一帧
        int earliest = Math.max(1, def.blendInTicks());
        int latest = Math.max(earliest, duration - 1);
        return Math.max(earliest, Math.min(bestTick, latest));
    }

    /** 过场开始：在镜头/动作进入画面后截一张（按过场 id 去重）。 */
    public static void onCutsceneStart(ClientAnimState.ActiveCutscene cutscene) {
        if (!enabled || cutscene == null || cutscene.cutsceneId.isBlank()) {
            return;
        }
        if (sequence >= MAX_SHOTS || !SEEN_CUTSCENE_IDS.add(cutscene.cutsceneId)) {
            return;
        }
        sequence++;
        String file = String.format(Locale.ROOT, "rpganim-%02d-cutscene-%s.png", sequence,
                sanitize(cutscene.cutsceneId));
        // 33% 处：boss_intro(180t) 落在第二个时间轴步骤（60t 起的 emote_point）附近，画面里有动作
        int at = Math.max(5, Math.min(cutscene.durationTicks - 1, (int) (cutscene.durationTicks * 0.33F)));
        SCHEDULED.add(new Scheduled(null, cutscene.cutsceneId, -1, at, file));
        log("shot-scheduled file=" + file + " cutscene=" + cutscene.cutsceneId + " at=" + at);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 客户端 tick
    // ─────────────────────────────────────────────────────────────────────

    public static void tick(Minecraft mc) {
        if (!enabled) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            if (inWorldLogged) {
                inWorldLogged = false;
                thirdPersonForced = false;
                frontCameraLogged = false;
                worldTicks = 0;
                attackCooldown = 0;
                emoteRequestCooldown = 0;
            }
            return;
        }
        if (!inWorldLogged) {
            inWorldLogged = true;
            worldTicks = 0;
            attackCooldown = ATTACK_INTERVAL_TICKS;
            // 首个 emote 请求延后：进世界瞬间还在「加载地形中」，此时触发动作只会得到废截图
            emoteRequestCooldown = 40;
            log("in-world player=" + mc.player.getGameProfile().name() + " pos=" + mc.player.blockPosition());
        }
        worldTicks++;
        if (!thirdPersonForced) {
            forceThirdPerson(mc);
            log("camera=THIRD_PERSON_BACK");
        }
        // autotest 期间：动作截图用第三人称正面（背后视角看不到格挡前伸/双臂过头，§7 目检要求），
        // 过场则必须回到背后（rpgcam 镜头是第三人称 back）。
        applyAutotestCamera(mc);
        tickAutoAttack(mc);
        tickAutoEmoteRequest(mc);
        promoteScheduledShots();
        if (pendingShot == null && !SHOT_QUEUE.isEmpty()) {
            requestShot(mc, SHOT_QUEUE.poll());
        }
        pollScreenshot(mc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 链路自检：真实攻击（→ 服务端 AFTER_DAMAGE → anim-trigger source=attack）
    // ─────────────────────────────────────────────────────────────────────

    /** 每 10 tick 对 4 格内最近的活体（排除自己/其他玩家）走真实攻击路径。 */
    private static void tickAutoAttack(Minecraft mc) {
        if (worldTicks < WORLD_SETTLE_TICKS) {
            return;
        }
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }
        attackCooldown = ATTACK_INTERVAL_TICKS;
        if (mc.gameMode == null || mc.player == null || mc.level == null || !mc.player.isAlive()) {
            return;
        }
        if (mc.gui.screen() != null) {
            return;
        }
        // 过场期间不插攻击：否则会顶掉过场 step 的动作，破坏「镜头+动作」截图取证
        if (ClientAnimState.currentCutscene() != null) {
            return;
        }
        // 有动作在播时先不攻击：避免顶掉 E2E 正在断言的 /anim play（否则 50% 截图和 anim-end 原因都会变）
        if (ClientAnimState.active(mc.player.getId()) != null) {
            return;
        }
        Entity target = findAttackTarget(mc);
        if (target == null) {
            return;
        }
        try {
            mc.gameMode.attack(mc.player, target);
            log("attack entity=" + target.getId() + " type=" + EntityType.getKey(target.getType()));
        } catch (RuntimeException exception) {
            RpgAnimClient.LOGGER.error(PREFIX + "attack failed entity={}", target.getId(), exception);
        }
    }

    private static Entity findAttackTarget(Minecraft mc) {
        AABB area = mc.player.getBoundingBox().inflate(ATTACK_RADIUS);
        List<Entity> candidates = mc.level.getEntities(mc.player, area,
                entity -> entity instanceof LivingEntity living
                        && living.isAlive()
                        && entity != mc.player
                        && !(entity instanceof Player));
        Entity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity candidate : candidates) {
            double distance = candidate.distanceToSqr(mc.player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearestDistance <= ATTACK_RADIUS * ATTACK_RADIUS ? nearest : null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 链路自检：C2S EmoteRequest（→ 服务端 anim-trigger source=emote-request）
    // ─────────────────────────────────────────────────────────────────────

    /** 进世界后约每 200 tick 发一次 emote 请求（走与 H 键完全相同的 C2S 路径）。 */
    private static void tickAutoEmoteRequest(Minecraft mc) {
        if (worldTicks < WORLD_SETTLE_TICKS) {
            return;
        }
        if (emoteRequestCooldown > 0) {
            emoteRequestCooldown--;
            return;
        }
        if (!ClientAnimState.hasContent() || ClientAnimState.currentCutscene() != null) {
            emoteRequestCooldown = EMOTE_REQUEST_RETRY_TICKS;
            return;
        }
        // 有动作在播时先不发：避免顶掉 E2E 正在断言的 /anim play（会让「自然过期」断言看到 replaced）
        if (mc.player != null && ClientAnimState.active(mc.player.getId()) != null) {
            emoteRequestCooldown = EMOTE_REQUEST_RETRY_TICKS;
            return;
        }
        emoteRequestCooldown = EMOTE_REQUEST_INTERVAL_TICKS;
        ClientNetworking.sendEmoteRequest(AUTOTEST_EMOTE_ID);
        log("emote-request anim=" + AUTOTEST_EMOTE_ID);
    }

    private static void promoteScheduledShots() {
        if (SCHEDULED.isEmpty()) {
            return;
        }
        Iterator<Scheduled> iterator = SCHEDULED.iterator();
        while (iterator.hasNext()) {
            Scheduled scheduled = iterator.next();
            int age = scheduled.isCutscene()
                    ? ClientAnimState.cutsceneAge()
                    : ClientAnimState.actionAge(scheduled.entityId, scheduled.animId);
            if (age < 0) {
                // 动作在 50% 之前就被替换/过期（例如 demo 间隔很短），放弃这张
                iterator.remove();
                log("shot-dropped file=" + scheduled.file + " reason=action-gone");
                continue;
            }
            if (age >= scheduled.targetTicks) {
                iterator.remove();
                SHOT_QUEUE.add(scheduled.file);
            }
        }
    }

    private static void forceThirdPerson(Minecraft mc) {
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        thirdPersonForced = true;
    }

    /**
     * autotest 机位管理：动作截图用第三人称**正面**（背后视角看不到「格挡前伸 / 双臂过头」这类姿势），
     * 过场时保持背后（rpgcam 的镜头基于第三人称 back，别去抢它的视角）。
     * 只在第一次进入正面时打一行日志；之后被别的代码切走会静默纠回，不刷日志。
     */
    private static void applyAutotestCamera(Minecraft mc) {
        CameraType desired = ClientAnimState.currentCutscene() != null
                ? CameraType.THIRD_PERSON_BACK
                : CameraType.THIRD_PERSON_FRONT;
        if (mc.options.getCameraType() == desired) {
            return;
        }
        mc.options.setCameraType(desired);
        if (desired == CameraType.THIRD_PERSON_FRONT && !frontCameraLogged) {
            frontCameraLogged = true;
            log("camera=THIRD_PERSON_FRONT");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 截图（照抄 rpgcam CameraAutotest 的「上一帧 + 轮询落盘」做法）
    // ─────────────────────────────────────────────────────────────────────

    private static void requestShot(Minecraft mc, String fileName) {
        if (pendingShot != null) {
            return;
        }
        // 截图前再保证一次机位：动作图必须正面（看得见手臂姿势），过场图必须背后（rpgcam 镜头）
        mc.options.setCameraType(ClientAnimState.currentCutscene() != null
                ? CameraType.THIRD_PERSON_BACK
                : CameraType.THIRD_PERSON_FRONT);
        File file = shotFile(mc, fileName);
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        pendingShot = fileName;
        pendingWait = 0;
        try {
            Screenshot.grab(mc.gameDirectory, fileName, mc.gameRenderer.mainRenderTarget(), 1,
                    message -> RpgAnimClient.LOGGER.info(PREFIX + "screenshot-callback {} {}",
                            fileName, message.getString()));
            log("shot-requested=" + fileName);
        } catch (Exception exception) {
            RpgAnimClient.LOGGER.error(PREFIX + "screenshot failed {}", fileName, exception);
            pendingShot = null;
        }
    }

    private static void pollScreenshot(Minecraft mc) {
        if (pendingShot == null) {
            return;
        }
        pendingWait++;
        File file = shotFile(mc, pendingShot);
        if (file.isFile() && file.length() > 0L) {
            log("shot-ok=" + pendingShot + " bytes=" + file.length() + " path=" + file.getAbsolutePath());
            pendingShot = null;
        } else if (pendingWait > 100) {
            log("shot-TIMEOUT=" + pendingShot);
            pendingShot = null;
        }
    }

    private static File shotFile(Minecraft mc, String fileName) {
        return new File(new File(mc.gameDirectory, "screenshots"), fileName);
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-z0-9_./-]", "_");
    }

    private static void log(String message) {
        RpgAnimClient.LOGGER.info(PREFIX + message);
    }
}
