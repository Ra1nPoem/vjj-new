package com.vjj.fx;

import java.util.Locale;

/**
 * VJJ-NEW 特效库的效果类型（模仿 ArcaneVortex 的「宇宙/奥术」视觉体系，但为原创实现）。
 *
 * <p>每种效果由客户端 {@code FxManager} 用程序化粒子/屏幕叠加渲染；服务端只广播
 * {@link FxPayload}（类型、位置、颜色、缩放、时长、随机种子），不依赖客户端资源。</p>
 */
public enum FxType {
    /** 环形冲击：地面扩散光环 + 上升尘光。 */
    RING("ring", "奥术光环", 0xFFFFC84D, 70),
    /** 星涡：螺旋上升的粒子漩涡。 */
    VORTEX("vortex", "星涡", 0xFF9B6BFF, 90),
    /** 光柱：垂直能量柱 + 顶部光环。 */
    BEAM("beam", "光柱", 0xFF66D9FF, 60),
    /** 冲击波：球面扩散 + 闪光。 */
    SHOCKWAVE("shockwave", "冲击波", 0xFFFF9E40, 45),
    /** 灵气：环绕玩家的星点光晕。 */
    AURA("aura", "灵气", 0xFF7CFC98, 100),
    /** 符文阵：地面旋转符文。 */
    RUNE("rune", "符文阵", 0xFFC080FF, 100),
    /** 屏幕闪光 + 爆点。 */
    FLASH("flash", "闪光", 0xFFFFFFFF, 14),
    /** 寰宇：全屏星云覆盖 + 缓慢星点。 */
    COSMIC("cosmic", "寰宇", 0xFF8A5CFF, 200),
    /** 爆裂：瞬时放射尘光 + 电火花。 */
    BURST("burst", "爆裂", 0xFFFF5555, 32),
    /** 棱光：彩色电火花环绕。 */
    PRISM("prism", "棱光", 0xFF55FFFF, 70),
    /** 大型地面奥术法阵（实体渲染，多层发光纹理 + 光柱 + 碎晶）。 */
    CIRCLE("circle", "奥术法阵", 0xFF8A5CFF, 300);

    public final String id;
    public final String displayName;
    public final int defaultColor;
    public final int defaultDurationTicks;

    FxType(String id, String displayName, int defaultColor, int defaultDurationTicks) {
        this.id = id;
        this.displayName = displayName;
        this.defaultColor = defaultColor;
        this.defaultDurationTicks = defaultDurationTicks;
    }

    public static FxType byId(String id) {
        if (id == null) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (FxType type : values()) {
            if (type.id.equals(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
