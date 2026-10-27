package com.vjj.fx;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * 奥术法阵实体：{@code vjjnew:arcane_circle}。
 *
 * <p>服务端由 {@code /vjj fx circle ...} 生成；客户端 {@code ArcaneCircleRenderer} 用多层发光纹理 +
 * 径向光柱 + 环绕碎晶 + 旋转符文绘制大型地面法阵（模仿 ArcaneVortex 的仪式法阵视觉，原创实现）。</p>
 *
 * <p>实体本身不可见、无碰撞、无重力，寿命到期由服务端移除；不写盘（临时演出效果）。</p>
 */
public class ArcaneCircleEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_COLOR =
            SynchedEntityData.defineId(ArcaneCircleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(ArcaneCircleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> DATA_SEED =
            SynchedEntityData.defineId(ArcaneCircleEntity.class, EntityDataSerializers.LONG);

    /** 法阵生效时对附近玩家应用的后处理（见 assets/vjjnew/post_effect/arcane_bloom.json）。 */
    public static final Identifier POST_EFFECT_ID = Identifier.fromNamespaceAndPath("vjjnew", "arcane_bloom");
    public static final Identifier POST_EFFECT_WARP_ID = Identifier.fromNamespaceAndPath("vjjnew", "arcane_warp");

    /** 仅服务端使用：剩余寿命 tick。 */
    private int lifetimeTicks = 300;
    private int ageTicks;

    public ArcaneCircleEntity(EntityType<? extends ArcaneCircleEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setSilent(true);
        this.setDeltaMovement(0.0, 0.0, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_COLOR, 0xFF8A5CFF);
        builder.define(DATA_SCALE, 1.0F);
        builder.define(DATA_SEED, 0L);
    }

    public void configure(int color, float scale, long seed, int lifetimeTicks) {
        this.entityData.set(DATA_COLOR, color);
        this.entityData.set(DATA_SCALE, Math.max(0.2F, Math.min(8.0F, scale)));
        this.entityData.set(DATA_SEED, seed);
        this.lifetimeTicks = Math.max(20, lifetimeTicks);
    }

    public void setLifetimeTicks(int lifetimeTicks) {
        this.lifetimeTicks = Math.max(20, lifetimeTicks);
    }

    public int getFxColor() {
        return this.entityData.get(DATA_COLOR);
    }

    public float getFxScale() {
        return this.entityData.get(DATA_SCALE);
    }

    public long getFxSeed() {
        return this.entityData.get(DATA_SEED);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            ageTicks++;
            if (ageTicks > lifetimeTicks) {
                this.discard();
                return;
            }
            // 环境粒子：符文圆环上的尘埃/星屑拖尾（服务端广播给附近玩家）
            if (ageTicks % 2 == 0) {
                float scale = this.getFxScale();
                int color = this.getFxColor() & 0x00FFFFFF;
                for (int i = 0; i < 3; i++) {
                    double angle = this.random.nextDouble() * Math.PI * 2.0;
                    double radius = (2.6 + this.random.nextDouble() * 2.2) * scale;
                    serverLevel.sendParticles(new DustParticleOptions(color, 1.2F),
                            this.getX() + Math.cos(angle) * radius,
                            this.getY() + 0.15 + this.random.nextDouble() * 0.7,
                            this.getZ() + Math.sin(angle) * radius,
                            1, 0.0, 0.04, 0.0, 0.0);
                }
                if (ageTicks % 10 == 0) {
                    double angle = this.random.nextDouble() * Math.PI * 2.0;
                    double radius = 2.9 * scale;
                    serverLevel.sendParticles(ParticleTypes.END_ROD,
                            this.getX() + Math.cos(angle) * radius,
                            this.getY() + 0.4 + this.random.nextDouble() * 1.4,
                            this.getZ() + Math.sin(angle) * radius,
                            1, 0.0, 0.05, 0.0, 0.0);
                }
            }
            // 法阵附近的玩家应用后处理（辉光/色散/暗角），玩家进入范围后自动补上
            if (ageTicks % 10 == 0) {
                for (ServerPlayer player : serverLevel.players()) {
                    if (player.position().distanceToSqr(this.position()) < 64.0 * 64.0) {
                        player.addPostEffect(POST_EFFECT_ID);
                        player.addPostEffect(POST_EFFECT_WARP_ID);
                    }
                }
            }
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        super.remove(reason);
        if (this.level() instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.players()) {
                player.removePostEffect(POST_EFFECT_ID);
                player.removePostEffect(POST_EFFECT_WARP_ID);
            }
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 192.0 * 192.0;
    }

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        // 临时效果：类型 noSave()，不会写盘；保留空实现满足 Entity 抽象契约。
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
    }
}
