package com.vjj.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vjj.fx.ArcaneCircleEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Random;
import java.awt.Color;
import com.vjj.bloom.BloomConfig;

/**
 * 奥术法阵渲染器 v2：大型地面仪式法阵（多层发光纹理）+ 径向光带 + 八向光柱 + 环绕碎晶 + 星屑。
 *
 * <p>视觉参考 ArcaneVortex 的法阵/宇宙光效（原创实现，不复制其 ARR 资产）：</p>
 * <ul>
 *   <li>细节层用 {@code entityTranslucentEmissive}（保留纹理细节/颜色，不会加法叠加成纯白）；</li>
 *   <li>柔光底盘/星屑用 {@code energySwirl} 加法混合，提亮但不过曝；</li>
 *   <li>径向光带用 {@code lightning} 加法彩色几何；光柱/碎晶用半透明自发光双面四边形。</li>
 * </ul>
 */
public class ArcaneCircleRenderer extends EntityRenderer<ArcaneCircleEntity, ArcaneCircleRenderState> {
    private static final Identifier TEX_OUTER = id("textures/fx/circle_outer.png");
    private static final Identifier TEX_GLYPHS = id("textures/fx/circle_glyphs.png");
    private static final Identifier TEX_INNER = id("textures/fx/circle_inner.png");
    private static final Identifier TEX_RAYS = id("textures/fx/circle_rays.png");
    private static final Identifier TEX_BEAM = id("textures/fx/beam.png");
    private static final Identifier TEX_SHARD = id("textures/fx/shard.png");
    private static final Identifier TEX_STAR = id("textures/fx/star4.png");
    private static final Identifier TEX_GLOW = id("textures/ui/glow.png");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("vjjnew", path);
    }

    public ArcaneCircleRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ArcaneCircleRenderState createRenderState() {
        return new ArcaneCircleRenderState();
    }

    @Override
    public void extractRenderState(ArcaneCircleEntity entity, ArcaneCircleRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.color = entity.getFxColor();
        state.scale = entity.getFxScale();
        state.seed = entity.getFxSeed();
        state.time = entity.level().getGameTime() + partialTick;
    }

    @Override
    public void submit(ArcaneCircleRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        float time = (float) (state.time % 100000.0);
        float scale = Math.max(0.2F, state.scale);
        int rgb = state.color & 0x00FFFFFF;
        float pulse = 0.72F + 0.18F * Mth.sin(time * 0.10F);
        float pulse2 = 0.62F + 0.22F * Mth.sin(time * 0.07F + 1.7F);
        int base = color(rgb, (int) (205 * pulse));
        int cyan = color(0x9FEBFF, (int) (190 * pulse));
        int violet = color(0xD9A6FF, (int) (185 * pulse2));
        int gold = color(0xFFD77A, (int) (180 * pulse2));
        int white = color(0xFFFFFF, (int) (200 * pulse));

        // 柔光底盘（低强度加法，仅作地面提亮）
        additiveGroundLayer(collector, poseStack, TEX_GLOW, scale * 6.0F, 0.0F, color(dim(rgb, 0.4F), 255), 0.004F);

        // 细节法阵层（半透明自发光，保留符文细节）
        emissiveGroundLayer(collector, poseStack, TEX_OUTER, scale * 4.9F, time * 0.008F, withAlpha(base, 32), 0.012F);
        emissiveGroundLayer(collector, poseStack, TEX_GLYPHS, scale * 4.15F, -time * 0.016F, withAlpha(cyan, 26), 0.018F);
        emissiveGroundLayer(collector, poseStack, TEX_INNER, scale * 3.25F, time * 0.028F, withAlpha(violet, 30), 0.024F);
        emissiveGroundLayer(collector, poseStack, TEX_RAYS, scale * 5.35F, -time * 0.005F, withAlpha(gold, 18), 0.030F);

        drawRays(collector, poseStack, scale, time, rgb);
        drawEnergyFaces(collector, poseStack, scale, time, cyan, violet, gold);
        drawBeams(collector, poseStack, scale, time, cyan, violet, gold);
        drawShards(collector, poseStack, scale, time, state.seed, cyan, violet, gold, white);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 地面层
    // ─────────────────────────────────────────────────────────────────────

    /** 半透明自发光地面层（单面，保留贴图颜色细节）。 */
    private void emissiveGroundLayer(SubmitNodeCollector collector, PoseStack poseStack, Identifier texture,
                                     float radius, float angle, int argb, float y) {
        RenderType type = RenderTypes.energySwirl(texture, 0.0F, 0.0F);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            float c = Mth.cos(angle);
            float s = Mth.sin(angle);
            float[][] corners = {{-radius, -radius}, {radius, -radius}, {radius, radius}, {-radius, radius}};
            float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                float lx = corners[i][0] * c - corners[i][1] * s;
                float lz = corners[i][0] * s + corners[i][1] * c;
                textured(buffer, pose, lx, y, lz, argb, uvs[i][0], uvs[i][1], 0.0F, 1.0F, 0.0F);
            }

        });
    }

    /** 加法柔光地面层（低 alpha，避免过曝）。 */
    private void additiveGroundLayer(SubmitNodeCollector collector, PoseStack poseStack, Identifier texture,
                                     float radius, float angle, int argb, float y) {
        RenderType type = RenderTypes.energySwirl(texture, 0.0F, 0.0F);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            float c = Mth.cos(angle);
            float s = Mth.sin(angle);
            float[][] corners = {{-radius, -radius}, {radius, -radius}, {radius, radius}, {-radius, radius}};
            float[][] uvs = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                float lx = corners[i][0] * c - corners[i][1] * s;
                float lz = corners[i][0] * s + corners[i][1] * c;
                textured(buffer, pose, lx, y, lz, argb, uvs[i][0], uvs[i][1], 0.0F, 1.0F, 0.0F);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 径向光带（加法彩色几何）
    // ─────────────────────────────────────────────────────────────────────

    /** 旋转的能量三角形刃面（加法彩色几何，参考法阵的三角形/棱锥能量面）。 */
    private void drawEnergyFaces(SubmitNodeCollector collector, PoseStack poseStack, float scale, float time,
                                 int cyan, int violet, int gold) {
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
            int count = Math.min(64, BloomConfig.maxPrismShards);
            for (int i = 0; i < count; i++) {
                float baseAngle = i * (float) Math.PI * 2.0F / count + time * 0.018F;
                float halfWidth = 0.11F;
                float pulse = 0.5F + 0.5F * Mth.sin(time * 0.1F + i * 1.7F);
                float inner = scale * (0.85F + 0.25F * pulse);
                float outer = scale * (4.05F + 0.55F * pulse);
                int tint = i % 3 == 0 ? gold : (i % 3 == 1 ? cyan : violet);
                int color = color(dim(tint, 0.20F + 0.22F * pulse), 255);
                float a1 = baseAngle - halfWidth;
                float a2 = baseAngle + halfWidth;
                // wedge: two center vertices (inner) + two outer vertices
                colored(buffer, pose, Mth.cos(baseAngle) * inner, 0.05F, Mth.sin(baseAngle) * inner, color);
                colored(buffer, pose, Mth.cos(baseAngle) * inner, 0.05F, Mth.sin(baseAngle) * inner, color);
                colored(buffer, pose, Mth.cos(a2) * outer, 0.05F, Mth.sin(a2) * outer, color);
                colored(buffer, pose, Mth.cos(a1) * outer, 0.05F, Mth.sin(a1) * outer, color);
            }
            // outer ring blade ring
            for (int i = 0; i < 24; i++) {
                float a = i * (float) Math.PI * 2.0F / 24.0F - time * 0.01F;
                float next = a + (float) Math.PI * 2.0F / 24.0F * 0.55F;
                float pulse = 0.5F + 0.5F * Mth.sin(time * 0.08F + i * 2.1F);
                int color = color(dim(i % 2 == 0 ? cyan : violet, 0.16F + 0.2F * pulse), 255);
                colored(buffer, pose, Mth.cos(a) * scale * 4.35F, 0.04F, Mth.sin(a) * scale * 4.35F, color);
                colored(buffer, pose, Mth.cos(next) * scale * 4.55F, 0.04F, Mth.sin(next) * scale * 4.55F, color);
                colored(buffer, pose, Mth.cos(next + 0.08F) * scale * 4.1F, 0.04F, Mth.sin(next + 0.08F) * scale * 4.1F, color);
                colored(buffer, pose, Mth.cos(a + 0.06F) * scale * 4.1F, 0.04F, Mth.sin(a + 0.06F) * scale * 4.1F, color);
            }
        });
    }
    private void drawRays(SubmitNodeCollector collector, PoseStack poseStack, float scale, float time, int rgb) {
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
            int count = 32;
            float width = 0.04F * scale;
            for (int i = 0; i < count; i++) {
                float angle = i * (float) Math.PI * 2.0F / count + time * 0.01F;
                float pulse = 0.5F + 0.5F * Mth.sin(time * 0.12F + i * 0.9F);
                float inner = scale * (0.7F + 0.25F * pulse);
                float outer = scale * (4.3F + 1.8F * pulse);
                int tint = i % 3 == 0 ? 0xFFF2C0 : (i % 3 == 1 ? 0x9FEBFF : rgb);
                int color = color(dim(tint, 0.55F + 0.15F * pulse), 255);
                float dx = Mth.cos(angle);
                float dz = Mth.sin(angle);
                float px = -dz * width;
                float pz = dx * width;
                colored(buffer, pose, dx * inner + px, 0.07F, dz * inner + pz, color);
                colored(buffer, pose, dx * inner - px, 0.07F, dz * inner - pz, color);
                colored(buffer, pose, dx * outer - px, 0.07F, dz * outer - pz, color);
                colored(buffer, pose, dx * outer + px, 0.07F, dz * outer + pz, color);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 光柱
    // ─────────────────────────────────────────────────────────────────────

    private void drawBeams(SubmitNodeCollector collector, PoseStack poseStack, float scale, float time,
                           int cyan, int violet, int gold) {
        RenderType type = RenderTypes.energySwirl(TEX_BEAM, 0.0F, 1.0F - (time * 0.02F) % 1.0F);
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            for (int i = 0; i < 8; i++) {
                float angle = i * (float) Math.PI * 2.0F / 8.0F + time * 0.006F;
                float radius = scale * 3.3F;
                float x = Mth.cos(angle) * radius;
                float z = Mth.sin(angle) * radius;
                float height = scale * (4.8F + 1.5F * (0.5F + 0.5F * Mth.sin(time * 0.06F + i)));
                float width = scale * 0.42F;
                int color = i % 3 == 0 ? gold : (i % 2 == 0 ? cyan : violet);
                int argb = color(dim(color, 0.75F), 255);
                for (int k = 0; k < 4; k++) {
                    float planeAngle = angle + k * (float) Math.PI / 4.0F;
                    verticalQuad(buffer, pose, x, 0.0F, z,
                            -Mth.sin(planeAngle) * width, 0.0F, Mth.cos(planeAngle) * width,
                            height, argb, 0.0F, 0.0F, 0.0F, 1.0F);
                }
            }
            float h = scale * 7.2F;
            float w = scale * 0.7F;
            int core = color(0xFFFFFF, 255);
            for (int k = 0; k < 4; k++) {
                float pa = k * (float) Math.PI / 4.0F;
                verticalQuad(buffer, pose, 0.0F, 0.0F, 0.0F, -Mth.sin(pa) * w, 0.0F, Mth.cos(pa) * w,
                        h, core, 0.0F, 0.0F, 0.0F, 1.0F);
            }
        });
    }

    /** 竖直双面四边形（UV 从底部到顶部）。 */
    private void verticalQuad(VertexConsumer buffer, PoseStack.Pose pose,
                              float x, float y, float z, float dx, float dy, float dz,
                              float height, int color, float u1, float v1, float u2, float v2) {
        float x2 = x + dx;
        float z2 = z + dz;
        textured(buffer, pose, x, y, z, color, u1, v2, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x2, y, z2, color, u2, v2, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x2, y + height, z2, color, u2, v1, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x, y + height, z, color, u1, v1, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x, y + height, z, color, u1, v1, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x2, y + height, z2, color, u2, v1, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x2, y, z2, color, u2, v2, 0.0F, 0.0F, 1.0F);
        textured(buffer, pose, x, y, z, color, u1, v2, 0.0F, 0.0F, 1.0F);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 碎晶 / 星屑
    // ─────────────────────────────────────────────────────────────────────

    private void drawShards(SubmitNodeCollector collector, PoseStack poseStack, float scale, float time,
                            long seed, int cyan, int violet, int gold, int white) {
        RenderType shardType = RenderTypes.energySwirl(TEX_SHARD, 0.0F, 0.0F);
        collector.submitCustomGeometry(poseStack, shardType, (pose, buffer) -> {
            int count = Math.min(64, BloomConfig.maxPrismShards);
            for (int i = 0; i < count; i++) {
                float angle = time * 0.018F + i * (float) Math.PI * 2.0F / count;
                float radius = scale * (1.9F + 1.05F * Mth.sin(time * 0.03F + i * 1.3F));
                float x = Mth.cos(angle) * radius;
                float z = Mth.sin(angle) * radius;
                float y = 1.0F + scale * (0.55F + 0.7F * Mth.sin(time * 0.05F + i * 1.7F));
                float size = scale * (0.22F + 0.07F * (0.5F + 0.5F * Mth.sin(time * 0.09F + i)));
                float hue = (time * 0.0022F + i * 0.043F) % 1.0F;
                int base = 0xFF000000 | (Color.HSBtoRGB(hue, 0.72F, 1.0F) & 0x00FFFFFF);
                int color = color(base, 255);
                textured(buffer, pose, x - size, y - size, z, color, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x + size, y - size, z, color, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x + size, y + size, z, color, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x - size, y + size, z, color, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x, y - size, z - size, color, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F);
                textured(buffer, pose, x, y - size, z + size, color, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F);
                textured(buffer, pose, x, y + size, z + size, color, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F);
                textured(buffer, pose, x, y + size, z - size, color, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F);
            }
        });

        // 星屑：小加法光点
        collector.submitCustomGeometry(poseStack, RenderTypes.energySwirl(TEX_STAR, 0.0F, 0.0F), (pose, buffer) -> {
            Random random = new Random(seed ^ 0x5DEECE66DL);
            int count = 64;
            for (int i = 0; i < count; i++) {
                float angle = random.nextFloat() * (float) Math.PI * 2.0F;
                float radius = scale * (0.8F + random.nextFloat() * 5.4F);
                float x = Mth.cos(angle) * radius;
                float z = Mth.sin(angle) * radius;
                float y = 0.2F + random.nextFloat() * scale * 3.8F;
                float twinkle = 0.35F + 0.65F * (0.5F + 0.5F * Mth.sin(time * 0.15F + i * 2.3F));
                float size = (0.035F + random.nextFloat() * 0.055F) * scale;
                int tint = i % 4 == 0 ? 0xFFF2C0 : (i % 4 == 1 ? 0x9FEBFF : white);
                int color = color(dim(tint, 0.85F * twinkle), 255);
                textured(buffer, pose, x - size, y - size, z, color, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x + size, y - size, z, color, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x + size, y + size, z, color, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
                textured(buffer, pose, x - size, y + size, z, color, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // 顶点辅助
    // ─────────────────────────────────────────────────────────────────────

    private static void textured(VertexConsumer buffer, PoseStack.Pose pose,
                                 float x, float y, float z, int argb, float u, float v,
                                 float nx, float ny, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(argb)
                .setUv(u, v)
                .setUv1(0, 10)
                .setUv2(240, 240)
                .setNormal(pose, nx, ny, nz);
    }

    private static void colored(VertexConsumer buffer, PoseStack.Pose pose,
                                float x, float y, float z, int argb) {
        buffer.addVertex(pose, x, y, z).setColor(argb);
    }

    private static int withAlpha(int argb, int alpha) {
        return ((alpha & 0xFF) << 24) | (argb & 0x00FFFFFF);
    }
    private static int color(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int dim(int rgb, float factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }
}
