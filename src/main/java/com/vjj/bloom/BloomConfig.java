package com.vjj.bloom;

/**
 * BloomSystem 风格渲染参数（1.26.3 等价实现）。
 *
 * <p>对应需求中的 YACL 配置项：Bloom 强度、色散强度、粒子上限、总开关。
 * 因当前整合包运行在 26.3，Veil/YACL 无对应版本，这里用不可变配置对象 + JSON 覆盖预留；
 * 后续迁移到 1.21.1 + Veil 时可直接替换为 YACL 实例。</p>
 */
public final class BloomConfig {
    public static boolean enabled = true;
    public static boolean hudEnabled = true;
    public static float bloomStrength = 1.0F;
    public static float dispersionStrength = 0.72F;
    public static int maxPrismShards = 48;
    public static boolean gpuShardsPreferred = true;

    private BloomConfig() {
    }

    /** 预留：供 YACL/JSON 载入时写入。 */
    public static void apply(float bloom, float dispersion, int shards, boolean on) {
        bloomStrength = Math.max(0.0F, bloom);
        dispersionStrength = Math.max(0.0F, dispersion);
        maxPrismShards = Math.max(8, Math.min(256, shards));
        enabled = on;
    }
}
