package com.vjj.subtitle;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 世界字幕实体：{@code vjjnew:subtitle}。
 *
 * <p>用途：RPG 服务器演出台词/旁白，显示在<b>世界空间</b>里（贴墙、悬空、漂浮均可），
 * 位置与朝向固定、不随玩家转动；客户端渲染器 {@code com.vjj.client.subtitle.VjjSubtitleRenderer}
 * 在文字平面<b>前后两面</b>都绘制文字（走到背面也能看），并且<b>不绘制任何背景板</b>
 * （不是原版 text_display 的悬浮字/黑框）。</p>
 *
 * <p>实体本身：不可见、无碰撞、无重力、不可推动/伤害、静音；可持久化（写盘），
 * 服务器重启后世界字幕仍在，直到管理员用 {@code /subtitle clearworld} 或 {@code /kill} 清除。</p>
 */
public class VjjSubtitleEntity extends Entity {
    public static final String TAG_TEXT = "vjjnew:subtitle_text";
    public static final String TAG_COLOR = "vjjnew:subtitle_color";
    public static final String TAG_SCALE = "vjjnew:subtitle_scale";
    public static final String TAG_SHADOW = "vjjnew:subtitle_shadow";
    public static final String TAG_DOUBLE_SIDED = "vjjnew:subtitle_double_sided";

    private static final EntityDataAccessor<String> DATA_TEXT =
            SynchedEntityData.defineId(VjjSubtitleEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_COLOR =
            SynchedEntityData.defineId(VjjSubtitleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(VjjSubtitleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_SHADOW =
            SynchedEntityData.defineId(VjjSubtitleEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_DOUBLE_SIDED =
            SynchedEntityData.defineId(VjjSubtitleEntity.class, EntityDataSerializers.BOOLEAN);

    public VjjSubtitleEntity(EntityType<? extends VjjSubtitleEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setSilent(true);
        this.setDeltaMovement(0.0, 0.0, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_TEXT, "");
        builder.define(DATA_COLOR, 0xFFFFFFFF);
        builder.define(DATA_SCALE, 1.0F);
        builder.define(DATA_SHADOW, true);
        builder.define(DATA_DOUBLE_SIDED, false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 同步字段
    // ─────────────────────────────────────────────────────────────────────

    public String getSubtitleText() {
        String value = this.entityData.get(DATA_TEXT);
        return value == null ? "" : value;
    }

    public int getSubtitleColor() {
        return this.entityData.get(DATA_COLOR);
    }

    public float getSubtitleScale() {
        return this.entityData.get(DATA_SCALE);
    }

    public boolean hasSubtitleShadow() {
        return this.entityData.get(DATA_SHADOW);
    }

    public boolean isDoubleSided() {
        return this.entityData.get(DATA_DOUBLE_SIDED);
    }

    /** 服务端一次性设置全部字幕字段（spawn 前调用即可随同步包发给客户端）。 */
    public void setSubtitle(String text, int color, float scale, boolean shadow, boolean doubleSided) {
        this.entityData.set(DATA_TEXT, text == null ? "" : text);
        this.entityData.set(DATA_COLOR, color);
        this.entityData.set(DATA_SCALE, Math.max(0.05F, Math.min(20.0F, scale)));
        this.entityData.set(DATA_SHADOW, shadow);
        this.entityData.set(DATA_DOUBLE_SIDED, doubleSided);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 被动 / 不参与 gameplay
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) {
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
        // 演出字幕在视野内就该显示：128 格内都渲染（默认实体约 64 格）
        return distanceSq < 128.0 * 128.0;
    }

    // ─────────────────────────────────────────────────────────────────────
    // NBT（世界字幕持久化：服务器重启后仍存在）
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(ValueOutput output) {
        output.putString(TAG_TEXT, this.getSubtitleText());
        output.putInt(TAG_COLOR, this.getSubtitleColor());
        output.putFloat(TAG_SCALE, this.getSubtitleScale());
        output.putBoolean(TAG_SHADOW, this.hasSubtitleShadow());
        output.putBoolean(TAG_DOUBLE_SIDED, this.isDoubleSided());
    }

    @Override
    public void readAdditionalSaveData(ValueInput input) {
        this.entityData.set(DATA_TEXT, input.getStringOr(TAG_TEXT, ""));
        this.entityData.set(DATA_COLOR, input.getIntOr(TAG_COLOR, 0xFFFFFFFF));
        this.entityData.set(DATA_SCALE, input.getFloatOr(TAG_SCALE, 1.0F));
        this.entityData.set(DATA_SHADOW, input.getBooleanOr(TAG_SHADOW, true));
        this.entityData.set(DATA_DOUBLE_SIDED, input.getBooleanOr(TAG_DOUBLE_SIDED, true));
    }
}
