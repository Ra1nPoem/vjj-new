package com.vjj.dummy;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 玩家假人实体：{@code vjjnew:dummy}。
 *
 * <p>用途：演出时在目标玩家位置放一个「玩家外观 + 目标皮肤 + rpganim 骨骼动作」的静态替身，
 * 本体被隐藏/锁行动（隐藏与锁定由 {@code com.vjj.scene.VjjSceneManager} / camera 模块负责，
 * 本实体只负责“站在那里播动作”）。</p>
 *
 * <p>26.3 实现要点：</p>
 * <ul>
 *   <li>无 AI（{@link #setNoAi(boolean)}）、无重力、静音、无碰撞（{@code noPhysics}）、不可推动、
 *       不可伤害、不自然消失（{@code persistenceRequired}）、不写盘（{@code EntityType.Builder.noSave()}，
 *       假人是临时演出道具，服务器重启后不应残留）；</li>
 *   <li>服务端每 tick 把 owner（生成它的玩家）的位置/朝向同步到假人 → 假人始终“站在本体位置”；</li>
 *   <li>动画通过 {@link SynchedEntityData} 同步到客户端：{@code animId + animSeq}（重播计数）+
 *       {@code loop + animDuration}；客户端 {@code com.vjj.client.dummy.VjjDummyClient} 按 seq 变化重启动画时钟；</li>
 *   <li>owner 用 UUID 两个 long + 玩家名同步（客户端据此解析目标玩家皮肤，无需额外网络包）。</li>
 * </ul>
 *
 * <p>该实体不参与任何 gameplay：不攻击、不被攻击、不推动、不捡东西、不叫、不消失。</p>
 */
public class VjjDummyEntity extends PathfinderMob {
    /** NBT / 日志字段名（假人默认不写盘；保留读写实现以便被 /data 或调试工具检查）。 */
    public static final String TAG_ANIM = "vjjnew:anim";
    public static final String TAG_ANIM_SEQ = "vjjnew:anim_seq";
    public static final String TAG_LOOP = "vjjnew:loop";
    public static final String TAG_ANIM_DURATION = "vjjnew:anim_duration";
    public static final String TAG_OWNER_MOST = "vjjnew:owner_most";
    public static final String TAG_OWNER_LEAST = "vjjnew:owner_least";
    public static final String TAG_OWNER_NAME = "vjjnew:owner_name";

    // ─────────────────────────────────────────────────────────────────────
    // 同步数据（客户端渲染/动画只用这些字段；服务端逻辑另存普通字段）
    // ─────────────────────────────────────────────────────────────────────
    private static final EntityDataAccessor<String> DATA_ANIM =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_ANIM_SEQ =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_OWNER_MOST =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_OWNER_LEAST =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<String> DATA_OWNER_NAME =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DATA_LOOP =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.BOOLEAN);
    /** 0 = 用动作内容里的默认时长；>0 = 覆盖（服务端场景可指定）。 */
    private static final EntityDataAccessor<Integer> DATA_ANIM_DURATION =
            SynchedEntityData.defineId(VjjDummyEntity.class, EntityDataSerializers.INT);

    /** 服务端：owner 离线后累计 tick，超时自毁（防止场景管理器漏清理留下孤儿实体）。 */
    private int orphanTicks;

    public VjjDummyEntity(EntityType<? extends VjjDummyEntity> type, Level level) {
        super(type, level);
        // 静态演出替身：完全被动
        this.setNoAi(true);
        this.setNoGravity(true);
        this.setPersistenceRequired();
        this.setSilent(true);
        this.setPermanentlyInvulnerable(true);
        this.setCanPickUpLoot(false);
        this.setCustomNameVisible(false);
        this.setDeltaMovement(Vec3.ZERO);
        // 不参与碰撞：本体与假人同坐标，若假人可碰撞会把玩家卡住/推走
        this.noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ANIM, "");
        builder.define(DATA_ANIM_SEQ, 0);
        builder.define(DATA_OWNER_MOST, 0L);
        builder.define(DATA_OWNER_LEAST, 0L);
        builder.define(DATA_OWNER_NAME, "");
        builder.define(DATA_LOOP, false);
        builder.define(DATA_ANIM_DURATION, 0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 同步字段读写
    // ─────────────────────────────────────────────────────────────────────

    public String getAnimId() {
        String value = this.entityData.get(DATA_ANIM);
        return value == null ? "" : value;
    }

    public int getAnimSeq() {
        return this.entityData.get(DATA_ANIM_SEQ);
    }

    public boolean isLoop() {
        return this.entityData.get(DATA_LOOP);
    }

    public int getAnimDuration() {
        return this.entityData.get(DATA_ANIM_DURATION);
    }

    public String getOwnerName() {
        String value = this.entityData.get(DATA_OWNER_NAME);
        return value == null ? "" : value;
    }

    /** owner UUID；没有 owner（未初始化/已清理）返回 {@code null}。 */
    public UUID getOwnerUuid() {
        long most = this.entityData.get(DATA_OWNER_MOST);
        long least = this.entityData.get(DATA_OWNER_LEAST);
        if (most == 0L && least == 0L) {
            return null;
        }
        return new UUID(most, least);
    }

    /** 服务端：记录 owner（生成假人的玩家）——客户端据此解析皮肤。 */
    public void setOwner(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        this.entityData.set(DATA_OWNER_MOST, uuid.getMostSignificantBits());
        this.entityData.set(DATA_OWNER_LEAST, uuid.getLeastSignificantBits());
        this.entityData.set(DATA_OWNER_NAME, player.getGameProfile().name());
        this.orphanTicks = 0;
    }

    /**
     * 服务端：设置/切换假人动作。{@code animSeq} 自增，客户端据此重启动画时钟
     * （即使同一个动画重播也会从 t=0 开始）。
     *
     * @param animId         动作 id（不能为空）
     * @param loop           是否循环
     * @param durationTicks  覆盖时长；{@code <=0} 用动作内容默认时长
     */
    public void applyAnim(String animId, boolean loop, int durationTicks) {
        this.entityData.set(DATA_ANIM, animId == null ? "" : animId);
        this.entityData.set(DATA_LOOP, loop);
        this.entityData.set(DATA_ANIM_DURATION, Math.max(0, durationTicks));
        this.entityData.set(DATA_ANIM_SEQ, this.entityData.get(DATA_ANIM_SEQ) + 1);
    }

    /** 服务端：清空动作（客户端会停止本地动作时钟，假人回到原版站姿）。 */
    public void clearAnim() {
        this.entityData.set(DATA_ANIM, "");
        this.entityData.set(DATA_ANIM_SEQ, this.entityData.get(DATA_ANIM_SEQ) + 1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // tick：服务端跟随 owner（位置/朝向）；owner 离线则自毁
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        // 绝对静止：不移动、不受重力/速度影响（位置由服务端跟随 owner 覆盖）
        this.setDeltaMovement(Vec3.ZERO);
        if (this.level().isClientSide()) {
            return;
        }
        MinecraftServer server = this.level().getServer();
        if (server == null) {
            return;
        }
        UUID ownerUuid = this.getOwnerUuid();
        ServerPlayer owner = ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) {
            // 场景管理器/掉线回调应当已清理；这里是兜底（防止任何路径漏掉导致永久残留）
            this.orphanTicks++;
            if (this.orphanTicks > 40) {
                this.discard();
            }
            return;
        }
        this.orphanTicks = 0;
        if (owner.level() != this.level()) {
            // 本体换维度：假人不应留在旧维度
            this.discard();
            return;
        }
        // 假人是静态站位：每 tick 对齐 owner 的位置与朝向（本体被锁/隐藏时位置不会漂）
        this.snapTo(owner.getX(), owner.getY(), owner.getZ(), owner.getYRot(), owner.getXRot());
        this.setYHeadRot(owner.getYHeadRot());
        this.setYBodyRot(owner.yBodyRot);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 无敌 / 被动
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    /** 假人没有声音（setSilent 已生效；避免子类/音效表在静音时仍被查询）。 */
    @Override
    public boolean isSilent() {
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // NBT（假人用 noSave 类型注册，正常不会写盘；保留完整读写以便调试/未来持久化）
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString(TAG_ANIM, this.getAnimId());
        output.putInt(TAG_ANIM_SEQ, this.getAnimSeq());
        output.putBoolean(TAG_LOOP, this.isLoop());
        output.putInt(TAG_ANIM_DURATION, this.getAnimDuration());
        UUID owner = this.getOwnerUuid();
        output.putLong(TAG_OWNER_MOST, owner == null ? 0L : owner.getMostSignificantBits());
        output.putLong(TAG_OWNER_LEAST, owner == null ? 0L : owner.getLeastSignificantBits());
        output.putString(TAG_OWNER_NAME, this.getOwnerName());
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_ANIM, input.getStringOr(TAG_ANIM, ""));
        this.entityData.set(DATA_ANIM_SEQ, input.getIntOr(TAG_ANIM_SEQ, 0));
        this.entityData.set(DATA_LOOP, input.getBooleanOr(TAG_LOOP, false));
        this.entityData.set(DATA_ANIM_DURATION, input.getIntOr(TAG_ANIM_DURATION, 0));
        this.entityData.set(DATA_OWNER_MOST, input.getLongOr(TAG_OWNER_MOST, 0L));
        this.entityData.set(DATA_OWNER_LEAST, input.getLongOr(TAG_OWNER_LEAST, 0L));
        this.entityData.set(DATA_OWNER_NAME, input.getStringOr(TAG_OWNER_NAME, ""));
    }
}
