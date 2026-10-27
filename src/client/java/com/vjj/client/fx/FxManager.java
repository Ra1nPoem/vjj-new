package com.vjj.client.fx;

import com.vjj.fx.FxPayload;
import com.vjj.fx.FxType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 客户端特效库运行时（模仿 ArcaneVortex 的「程序化奇观」风格，原创实现）。
 *
 * <p>收到 {@link FxPayload} 后创建一条 {@link Active}，在存活 tick 内按类型生成粒子：
 * 环、星涡、光柱、冲击波、灵气、符文阵、闪光、爆裂、棱光。屏幕类效果（FLASH/COSMIC）
 * 由 {@link ArcaneFxOverlay} 负责；文字提示由 {@link ArcaneToastHud} 负责。</p>
 *
 * <p>只用原版粒子（彩色 DUST + END_ROD/ELECTRIC_SPARK/ENCHANT 等）实现，不需要自定义着色器，
 * 但视觉语言（紫蓝星云、发光尘埃、旋转符文）对齐 ArcaneVortex 渲染库。</p>
 */
public final class FxManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("vjjnew-client");
    private static final List<Active> ACTIVE = new ArrayList<>();
    private static final Random RANDOM = new Random();

    private FxManager() {
    }

    public static void onPayload(FxPayload payload) {
        if (payload == null) {
            return;
        }
        FxType type = payload.effect();
        if (type == null) {
            LOGGER.warn("[VjjNew] fx-payload-unknown type={}", payload.typeId());
            return;
        }
        if (type == FxType.CIRCLE) {
            LOGGER.info("[VjjNew] fx-circle-payload-ignored（法阵走实体渲染）");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        ArcaneToastHud.push(type);
        if (type == FxType.FLASH || type == FxType.COSMIC) {
            ArcaneFxOverlay.trigger(type, payload.color(), payload.scale(),
                    payload.durationTicks() > 0 ? payload.durationTicks() : type.defaultDurationTicks, payload.seed());
        }
        int duration = payload.durationTicks() > 0 ? payload.durationTicks() : type.defaultDurationTicks;
        ACTIVE.add(new Active(type, payload.x(), payload.y(), payload.z(),
                payload.color(), (float) payload.scale(), duration, payload.seed()));
        LOGGER.info("[VjjNew] fx-receive type={} pos={},{},{} color={} scale={} duration={}",
                type.id, payload.x(), payload.y(), payload.z(), Integer.toHexString(payload.color()),
                payload.scale(), duration);
    }

    /** 本地预览（不经过服务端，供 FX Lab / 客户端命令用）。 */
    public static void preview(FxType type, int color, float scale, int durationTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Vec3 pos = minecraft.player.position();
        onPayload(new FxPayload(type.id, pos.x, pos.y, pos.z, color, scale, durationTicks, RANDOM.nextLong()));
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null) {
            ACTIVE.clear();
            return;
        }
        ClientLevel level = minecraft.level;
        Iterator<Active> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Active active = iterator.next();
            active.age++;
            if (active.age > active.duration) {
                iterator.remove();
                continue;
            }
            emit(active, level);
        }
    }

    public static void clear() {
        ACTIVE.clear();
        ArcaneFxOverlay.clear();
        ArcaneToastHud.clear();
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 各效果的程序化粒子
    // ─────────────────────────────────────────────────────────────────────

    private static void emit(Active active, ClientLevel level) {
        int age = active.age;
        int duration = Math.max(1, active.duration);
        float t = age / (float) duration;
        float scale = active.scale;
        double x = active.x;
        double y = active.y;
        double z = active.z;
        switch (active.type) {
            case RING -> {
                double radius = 0.25 + age * 0.11 * scale;
                for (int i = 0; i < 20; i++) {
                    double angle = i * Math.PI * 2.0 / 20.0;
                    double px = x + Math.cos(angle) * radius;
                    double pz = z + Math.sin(angle) * radius;
                    dust(level, px, y + 0.05, pz, active.color, 0.9F,
                            Math.cos(angle) * 0.02, 0.01, Math.sin(angle) * 0.02);
                }
                if (age % 6 == 0) {
                    for (int i = 0; i < 4; i++) {
                        double angle = i * Math.PI / 2.0 + age * 0.05;
                        endRod(level, x + Math.cos(angle) * radius, y + 0.1, z + Math.sin(angle) * radius,
                                0.0, 0.10, 0.0);
                    }
                }
            }
            case VORTEX -> {
                for (int i = 0; i < 10; i++) {
                    double angle = age * 0.28 + i * Math.PI * 2.0 / 10.0;
                    double radius = (1.25 * scale) * (1.0 - 0.35 * t) + 0.12;
                    double py = y + age * 0.025 * scale + (i % 3) * 0.15;
                    dust(level, x + Math.cos(angle) * radius, py, z + Math.sin(angle) * radius,
                            active.color, 0.9F, -Math.sin(angle) * 0.02, 0.06, Math.cos(angle) * 0.02);
                }
                if (age % 3 == 0) {
                    enchant(level, x + (RANDOM.nextDouble() - 0.5) * scale, y + RANDOM.nextDouble() * 2.4 * scale,
                            z + (RANDOM.nextDouble() - 0.5) * scale, 0.0, 0.02, 0.0);
                }
            }
            case BEAM -> {
                double height = 2.6 * scale;
                for (int i = 0; i < 8; i++) {
                    double py = y + RANDOM.nextDouble() * height;
                    double spread = 0.16 * scale;
                    dust(level, x + (RANDOM.nextDouble() - 0.5) * spread, py,
                            z + (RANDOM.nextDouble() - 0.5) * spread, active.color, 1.1F, 0.0, 0.03, 0.0);
                }
                if (age % 4 == 0) {
                    electric(level, x, y + RANDOM.nextDouble() * height, z, 0.0, 0.05, 0.0);
                }
                if (age % 10 == 0) {
                    for (int i = 0; i < 12; i++) {
                        double angle = i * Math.PI * 2.0 / 12.0;
                        dust(level, x + Math.cos(angle) * 0.7 * scale, y + height,
                                z + Math.sin(angle) * 0.7 * scale, active.color, 0.8F, 0.0, 0.04, 0.0);
                    }
                }
            }
            case SHOCKWAVE -> {
                double radius = 0.4 + age * 0.11 * scale;
                for (int i = 0; i < 14; i++) {
                    double theta = RANDOM.nextDouble() * Math.PI * 2.0;
                    double phi = Math.acos(2 * RANDOM.nextDouble() - 1);
                    double dx = Math.sin(phi) * Math.cos(theta);
                    double dy = Math.cos(phi);
                    double dz = Math.sin(phi) * Math.sin(theta);
                    dust(level, x + dx * radius, y + 0.4 + dy * radius, z + dz * radius,
                            active.color, 0.9F, dx * 0.06, dy * 0.06, dz * 0.06);
                }
                if (age % 8 == 0) {
                    endRod(level, x, y + 0.3, z, 0.0, 0.12, 0.0);
                }
            }
            case AURA -> {
                for (int i = 0; i < 6; i++) {
                    double angle = age * 0.22 + i * Math.PI * 2.0 / 6.0;
                    double radius = 0.85 * scale;
                    dust(level, x + Math.cos(angle) * radius, y + 0.7 + Math.sin(age * 0.15 + i) * 0.2,
                            z + Math.sin(angle) * radius, active.color, 0.8F,
                            -Math.sin(angle) * 0.01, 0.01, Math.cos(angle) * 0.01);
                }
                if (age % 7 == 0) {
                    enchant(level, x + (RANDOM.nextDouble() - 0.5) * 1.6, y + 0.4 + RANDOM.nextDouble() * 1.2,
                            z + (RANDOM.nextDouble() - 0.5) * 1.6, 0.0, 0.01, 0.0);
                }
            }
            case RUNE -> {
                double radius = 1.65 * scale;
                for (int i = 0; i < 24; i++) {
                    double angle = i * Math.PI * 2.0 / 24.0 + age * 0.02;
                    dust(level, x + Math.cos(angle) * radius, y + 0.03, z + Math.sin(angle) * radius,
                            active.color, 0.85F, 0.0, 0.01, 0.0);
                }
                if (age % 4 == 0) {
                    for (int i = 0; i < 4; i++) {
                        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                        double r = RANDOM.nextDouble() * radius;
                        dust(level, x + Math.cos(angle) * r, y + 0.04, z + Math.sin(angle) * r,
                                active.color, 0.7F, 0.0, 0.0, 0.0);
                    }
                }
                if (age % 9 == 0) {
                    enchant(level, x + (RANDOM.nextDouble() - 0.5) * radius * 2, y + 0.6,
                            z + (RANDOM.nextDouble() - 0.5) * radius * 2, 0.0, 0.02, 0.0);
                }
            }
            case BURST -> {
                if (age <= 6) {
                    for (int i = 0; i < 26; i++) {
                        double theta = RANDOM.nextDouble() * Math.PI * 2.0;
                        double phi = Math.acos(2 * RANDOM.nextDouble() - 1);
                        double dx = Math.sin(phi) * Math.cos(theta);
                        double dy = Math.cos(phi);
                        double dz = Math.sin(phi) * Math.sin(theta);
                        dust(level, x + dx * 0.2, y + 1.0 + dy * 0.2, z + dz * 0.2,
                                active.color, 1.1F, dx * 0.12, dy * 0.12, dz * 0.12);
                    }
                }
                if (age % 3 == 0) {
                    for (int i = 0; i < 4; i++) {
                        electric(level, x + (RANDOM.nextDouble() - 0.5) * scale, y + RANDOM.nextDouble() * 2.0,
                                z + (RANDOM.nextDouble() - 0.5) * scale, 0.0, 0.02, 0.0);
                    }
                }
            }
            case PRISM -> {
                for (int i = 0; i < 8; i++) {
                    double angle = age * 0.18 + i * Math.PI * 2.0 / 8.0;
                    double radius = 1.1 * scale;
                    electric(level, x + Math.cos(angle) * radius, y + 0.9 + Math.sin(age * 0.2 + i) * 0.6,
                            z + Math.sin(angle) * radius, 0.0, 0.01, 0.0);
                    dust(level, x + Math.cos(angle) * radius, y + 0.9 + Math.sin(age * 0.2 + i) * 0.6,
                            z + Math.sin(angle) * radius, active.color, 0.7F, 0.0, 0.0, 0.0);
                }
            }
            case FLASH -> {
                if (age <= 5) {
                    for (int i = 0; i < 18; i++) {
                        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                        dust(level, x + Math.cos(angle) * 0.3, y + 0.8 + RANDOM.nextDouble() * 0.4,
                                z + Math.sin(angle) * 0.3, active.color, 1.0F,
                                Math.cos(angle) * 0.08, 0.02, Math.sin(angle) * 0.08);
                    }
                }
            }
            case COSMIC -> {
                if (age % 3 == 0) {
                    for (int i = 0; i < 2; i++) {
                        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                        double radius = (0.5 + RANDOM.nextDouble() * 2.0) * scale;
                        dust(level, x + Math.cos(angle) * radius, y + 0.5 + RANDOM.nextDouble() * 2.2,
                                z + Math.sin(angle) * radius, active.color, 0.7F, 0.0, 0.008, 0.0);
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 粒子辅助
    // ─────────────────────────────────────────────────────────────────────

    private static void dust(ClientLevel level, double x, double y, double z, int color, float size,
                             double vx, double vy, double vz) {
        level.addParticle(new DustParticleOptions(color & 0x00FFFFFF, size), x, y, z, vx, vy, vz);
    }

    private static void endRod(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(ParticleTypes.END_ROD, x, y, z, vx, vy, vz);
    }

    private static void electric(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, vx, vy, vz);
    }

    private static void enchant(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        level.addParticle(ParticleTypes.ENCHANT, x, y, z, vx, vy, vz);
    }

    /** 一条存活中的特效。 */
    private static final class Active {
        final FxType type;
        final double x;
        final double y;
        final double z;
        final int color;
        final float scale;
        final int duration;
        final long seed;
        int age;

        Active(FxType type, double x, double y, double z, int color, float scale, int duration, long seed) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.scale = scale;
            this.duration = duration;
            this.seed = seed;
        }
    }
}
