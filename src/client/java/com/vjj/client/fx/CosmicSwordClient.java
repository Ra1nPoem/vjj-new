package com.vjj.client.fx;

import com.vjj.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

import java.util.Random;

/**
 * 宇宙之刃客户端特效：手持时星尘/电光环绕，挥砍时沿弧线留下紫青拖尾。
 *
 * <p>注意：粒子不能生成在摄像机前方 1 格内，否则近距离粒子会糊满屏幕（早期版本踩过的坑）。
 * 因此常驻光环以角色腰部为中心，挥砍拖尾至少离眼睛 1.5 格。</p>
 */
public final class CosmicSwordClient {
    private static final Random RANDOM = new Random();
    private static boolean hitTriggered;

    private CosmicSwordClient() {
    }

    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        boolean holding = player.getMainHandItem().is(ModItems.COSMIC_SWORD)
                || player.getOffhandItem().is(ModItems.COSMIC_SWORD);
        if (!holding) {
            return;
        }
        long time = minecraft.level.getGameTime();
        Vec3 look = player.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0, look.x);
        if (right.lengthSqr() < 1.0E-4) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }

        // 常驻光环：绕角色腰部/肩部的星尘，离镜头至少 1 格
        Vec3 auraBase = player.position()
                .add(0.0, 1.05, 0.0)
                .add(right.scale(0.55));
        if (time % 3 == 0) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double radius = 0.55 + RANDOM.nextDouble() * 0.25;
            double px = auraBase.x + Math.cos(angle) * radius;
            double pz = auraBase.z + Math.sin(angle) * radius;
            double py = auraBase.y + (RANDOM.nextDouble() - 0.5) * 0.7;
            minecraft.level.addParticle(new DustParticleOptions(
                            RANDOM.nextBoolean() ? 0x8A5CFF : 0x9FEBFF, 0.7F),
                    px, py, pz, 0.0, 0.008, 0.0);
            if (time % 9 == 0) {
                minecraft.level.addParticle(ParticleTypes.END_ROD, px, py, pz, 0.0, 0.01, 0.0);
            }
            if (time % 6 == 0) {
                minecraft.level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 0.0, 0.01, 0.0);
            }
        }

        float swing = player.getSwingAnimation(0.5F);
        // 命中特效：挥砍到目标时在目标位置爆开星尘/电光（客户端本地表现）
        if (swing > 0.55F && !hitTriggered && minecraft.hitResult instanceof EntityHitResult entityHit) {
            hitTriggered = true;
            Entity target = entityHit.getEntity();
            Vec3 center = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
            for (int i = 0; i < 18; i++) {
                Vec3 dir = new Vec3(RANDOM.nextDouble() - 0.5, RANDOM.nextDouble() - 0.3, RANDOM.nextDouble() - 0.5).normalize();
                Vec3 pos = center.add(dir.scale(0.25));
                minecraft.level.addParticle(new DustParticleOptions(
                                i % 2 == 0 ? 0x9FEBFF : 0xC58BFF, 1.0F),
                        pos.x, pos.y, pos.z, dir.x * 0.12, dir.y * 0.12, dir.z * 0.12);
                if (i % 3 == 0) {
                    minecraft.level.addParticle(ParticleTypes.END_ROD, pos.x, pos.y, pos.z,
                            dir.x * 0.08, dir.y * 0.08, dir.z * 0.08);
                }
                if (i % 4 == 0) {
                    minecraft.level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z,
                            dir.x * 0.1, dir.y * 0.1, dir.z * 0.1);
                }
            }
            minecraft.level.playLocalSound(center.x, center.y, center.z,
                    SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 0.8F, 1.35F, false);
        }
        if (swing <= 0.05F) {
            hitTriggered = false;
        }
        // 挥砍拖尾：从身体侧面向斜前方，至少离镜头 1.6 格
        if (swing > 0.02F) {
            double sweep = Math.sin(swing * Math.PI);
            Vec3 trail = player.position()
                    .add(0.0, 1.0, 0.0)
                    .add(look.scale(1.7))
                    .add(right.scale(0.5 - sweep * 0.65));
            for (int i = 0; i < 3; i++) {
                double t = i / 2.0;
                Vec3 pos = auraBase.lerp(trail, t);
                minecraft.level.addParticle(new DustParticleOptions(i % 2 == 0 ? 0x9FEBFF : 0xC58BFF, 0.6F),
                        pos.x, pos.y, pos.z, 0.0, 0.0, 0.0);
                if (i == 1) {
                    minecraft.level.addParticle(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 0.0, 0.01, 0.0);
                }
            }
        }
    }
}
