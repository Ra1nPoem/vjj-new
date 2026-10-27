package com.rpgcam.client;

import com.rpgcam.cue.CueSpec;
import com.rpgcam.cue.MotionType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 镜头轨迹数学：把「样式参数 + 进度 + 锚点」算成相机世界坐标/朝向。
 *
 * <p>纯函数、无状态、不碰渲染线程以外的对象，方便单独复核与写单元测试。六种
 * {@link MotionType} 不只是缓动曲线不同，轨迹形状也不同：</p>
 * <ul>
 *   <li><b>ORBIT</b>：固定距离绕锚点扫角，半径带轻微“呼吸”，避免圆规画圆的机械感；</li>
 *   <li><b>DOLLY</b>：距离线性推进/拉远，过程中沿切线轻微侧移形成弧线；</li>
 *   <li><b>CRANE</b>：高度主导，升降时水平缓慢漂移，形成摇臂弧线；</li>
 *   <li><b>HANDHELD</b>：三频叠加抖动，幅度随玩家移动速度增大（走路更晃）；</li>
 *   <li><b>DROP</b>：二次缓动快速下坠 + 缓慢前压，配合 ANCHOR_CENTER 形成倒地俯视；</li>
 *   <li><b>HOVER</b>：极慢呼吸漂移，几乎静止的展示机位。</li>
 * </ul>
 *
 * <p>另外提供从锚点眼位到目标机位的方块射线避让（26.3 {@code Level#clip(ClipContext)}，
 * 已用 javap 确认签名），保证镜头不穿墙、不进方块。</p>
 */
final class CameraMath {
    /** 相机与方块命中面之间保留的安全距离（米），避免近裁剪面切进方块。 */
    private static final double SAFE_MARGIN = 0.42;

    private CameraMath() {
    }

    // ─────────────────────────────────────────────────────────────────────
    // 进度缓动：每种 motion 的“快慢节奏”
    // ─────────────────────────────────────────────────────────────────────

    static double ease(MotionType motion, float t) {
        return switch (motion) {
            case ORBIT -> easeInOutCubic(t);                              // 起停柔和、中段快
            case DOLLY -> easeInOutCubic(t);                              // 推/拉同样柔和
            case CRANE -> easeInOutSine(t);                               // 升降匀速感更强
            case DROP -> easeInQuad(t);                                   // 越掉越快
            case HANDHELD -> 0.30 * t + 0.70 * easeInOutSine(t);          // 跟拍略带线性
            case HOVER -> easeInOutSine(t);                               // 几乎静止
        };
    }

    // ─────────────────────────────────────────────────────────────────────
    // 轨迹
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 计算相机世界坐标（未做方块避让，避让见 {@link #clampToWorld}）。
     *
     * @param t          归一化进度 0..1
     * @param e          该 motion 的缓动进度
     * @param baseYawDeg 锚点实体朝向（无实体时用玩家视线 yaw）；startAngle/sweep 都相对它
     * @param anchorEye  锚点眼睛位置
     * @param seed       服务端下发的种子（手持/悬停噪声可复现）
     * @param walkAmount 玩家当前水平速度（0 静止；用于手持抖动幅度）
     */
    static Vec3 position(CueSpec spec, float t, double e, double baseYawDeg, Vec3 anchorEye,
                         int seed, double walkAmount) {
        double angleRad = Math.toRadians(baseYawDeg + spec.startAngleDeg() + spec.sweepDeg() * e);
        double dist = lerp(e, spec.startDist(), spec.endDist());
        double height = lerp(e, spec.startHeight(), spec.endHeight());

        // 极坐标 → 世界坐标：yaw=0 朝 +Z，yaw=90 朝 -X（与 MC 一致）
        double radialX = -Math.sin(angleRad);
        double radialZ = Math.cos(angleRad);
        double tangentX = Math.cos(angleRad);
        double tangentZ = Math.sin(angleRad);

        double x = anchorEye.x + radialX * dist;
        double z = anchorEye.z + radialZ * dist;
        double y = anchorEye.y + height;
        double drift = spec.drift();

        switch (spec.motion()) {
            case ORBIT -> {
                // 半径呼吸：sin 一个整周期，起止回到原半径（不会和 sweep 打架）
                double breathe = Math.sin(t * Math.PI * 2.0) * 0.05 * dist;
                x += radialX * breathe;
                z += radialZ * breathe;
            }
            case DOLLY -> {
                // 沿切线侧移（中间最大），推近/拉远不再是死直线
                double sway = Math.sin(t * Math.PI) * 0.18;
                x += tangentX * sway;
                z += tangentZ * sway;
            }
            case CRANE -> {
                // 升降时的水平漂移，配合 look 中心形成摇臂弧线
                double sway = Math.sin(t * Math.PI) * 0.22;
                x += tangentX * sway;
                z += tangentZ * sway;
            }
            case HANDHELD -> {
                // 三频叠加的手持抖动，幅度随移动速度放大
                double amp = drift * (1.0 + 2.2 * Math.min(1.0, walkAmount));
                double freq = 1.0 + (seed & 7) * 0.05;
                double phase = (seed & 255) * 0.031;
                x += Math.sin(t * 17.3 * freq + phase) * amp;
                y += Math.sin(t * 23.1 * freq + phase * 1.7) * amp * 0.62;
                z += Math.cos(t * 19.7 * freq + phase * 2.3) * amp;
            }
            case HOVER -> {
                // 极慢呼吸漂移（与手持的高频抖动区分开）
                double freq = 1.0 + (seed & 3) * 0.15;
                x += Math.sin(t * 2.4 * freq + seed * 0.37) * drift * 1.4;
                y += Math.sin(t * 1.9 * freq + seed * 0.71) * drift * 0.8;
                z += Math.cos(t * 2.1 * freq + seed * 0.53) * drift * 1.4;
            }
            case DROP -> {
                // 坠落：先快后慢地远离一点，避免相机和倒地的角色重叠
                double push = t * t * 0.35;
                x += radialX * push;
                z += radialZ * push;
            }
        }

        Vec3 pos = new Vec3(x, y, z);

        // 开场冲击抖动（前 55% 时间，二次衰减）
        if (spec.shake() > 0.0F && t < 0.55F) {
            double decay = (0.55F - t) / 0.55F;
            double amp = spec.shake() * decay * decay;
            pos = pos.add(Math.sin(t * 260.0) * amp,
                    Math.cos(t * 190.0) * amp * 0.6,
                    Math.sin(t * 310.0 + 1.3) * amp);
        }
        return pos;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 方块避让 / 朝向
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 从锚点眼位到目标机位做方块射线检测；命中方块时把机位退到命中点前
     * {@link #SAFE_MARGIN} 米（26.3：{@code Level.clip(ClipContext)} → {@code BlockHitResult}）。
     */
    static Vec3 clampToWorld(Level level, Vec3 from, Vec3 desired) {
        if (level == null) {
            return desired;
        }
        Vec3 delta = desired.subtract(from);
        double full = delta.length();
        if (full < 1.0E-4) {
            return desired;
        }
        try {
            BlockHitResult hit = level.clip(new ClipContext(from, desired,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                double hitDist = hit.getLocation().distanceTo(from);
                if (hitDist < full) {
                    double safeRatio = Math.max(0.0, hitDist - SAFE_MARGIN) / full;
                    return from.add(delta.scale(safeRatio));
                }
            }
        } catch (Exception ignored) {
            // 世界未就绪/区块卸载等极端情况下宁可穿模也不要让演出崩掉
        }
        return desired;
    }

    /** 从相机位置看向目标点的 yaw（度，MC 约定）。 */
    static float yawTowards(Vec3 from, Vec3 lookAt) {
        Vec3 dir = lookAt.subtract(from);
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    /** 从相机位置看向目标点的 pitch（度，MC 约定：向下为正）。 */
    static float pitchTowards(Vec3 from, Vec3 lookAt) {
        Vec3 dir = lookAt.subtract(from);
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, horizontal));
        return Mth.clamp(pitch, -89.9F, 89.9F);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 数值
    // ─────────────────────────────────────────────────────────────────────

    static double lerp(double delta, double start, double end) {
        return start + (end - start) * delta;
    }

    private static double easeInOutCubic(double t) {
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
    }

    private static double easeInQuad(double t) {
        return t * t;
    }

    private static double easeInOutSine(double t) {
        return -(Math.cos(Math.PI * t) - 1.0) / 2.0;
    }

    /** 黑边进出场 ramp：前后 {@code rampTicks} 内 0→1→0。 */
    static float envelope(float t, int durationTicks, int rampTicks) {
        if (rampTicks <= 0 || durationTicks <= 0) {
            return 1.0F;
        }
        float in = Mth.clamp(t * durationTicks / rampTicks, 0.0F, 1.0F);
        float out = Mth.clamp((1.0F - t) * durationTicks / rampTicks, 0.0F, 1.0F);
        return Math.min(in, out);
    }

    /** 黑场不透明度：片头从 1 淡出、片尾渐入到 1。 */
    static float fadeAlpha(float t, int durationTicks, int fadeInTicks, int fadeOutTicks) {
        if (durationTicks <= 0) {
            return 0.0F;
        }
        float alpha = 0.0F;
        if (fadeInTicks > 0) {
            alpha = Math.max(alpha, 1.0F - Mth.clamp(t * durationTicks / fadeInTicks, 0.0F, 1.0F));
        }
        if (fadeOutTicks > 0) {
            alpha = Math.max(alpha, 1.0F - Mth.clamp((1.0F - t) * durationTicks / fadeOutTicks, 0.0F, 1.0F));
        }
        return alpha;
    }
}
