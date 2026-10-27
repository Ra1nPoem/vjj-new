package com.vjj.store;

/**
 * 运镜关键帧（VJJ-NEW 可编辑镜头的最小单位）。
 *
 * <p>坐标语义由所属 {@link CameraClip#mode} 决定：</p>
 * <ul>
 *   <li>{@code relative}：{@code x/y/z} 是相对“锚点眼睛位置”的偏移（方块）。默认模式，编辑时不需要知道世界坐标；</li>
 *   <li>{@code world}：{@code x/y/z} 是世界绝对坐标（捕获当前机位时使用）。</li>
 * </ul>
 *
 * <p>{@link #ease} 只作用于从本关键帧到下一关键帧这一段：{@code linear} / {@code smooth} / {@code step}。</p>
 */
public final class CameraKey {
    /** 相对整段镜头开始的 tick。 */
    public int tick;
    public double x;
    public double y;
    public double z;
    /** 镜头朝向（度）。 */
    public float yaw;
    public float pitch;
    /** 该关键帧处的 FOV（度）；<=0 表示沿用原版 FOV。 */
    public float fov = 70.0F;
    public String ease = "smooth";

    public CameraKey() {
    }

    public CameraKey(int tick, double x, double y, double z, float yaw, float pitch, float fov, String ease) {
        this.tick = tick;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fov = fov;
        this.ease = ease == null || ease.isBlank() ? "smooth" : ease;
    }

    public CameraKey copy() {
        return new CameraKey(this.tick, this.x, this.y, this.z, this.yaw, this.pitch, this.fov, this.ease);
    }

    public String easeOrDefault() {
        return this.ease == null || this.ease.isBlank() ? "smooth" : this.ease;
    }

    @Override
    public String toString() {
        return "key[t=" + this.tick + " pos=" + fmt(this.x) + "," + fmt(this.y) + "," + fmt(this.z)
                + " rot=" + fmt(this.yaw) + "," + fmt(this.pitch) + " fov=" + fmt(this.fov)
                + " ease=" + easeOrDefault() + "]";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
