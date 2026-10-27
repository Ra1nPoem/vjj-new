package com.vjj.subtitle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C：在玩家 UI 屏幕上显示一句字幕。
 *
 * <p>位置是**屏幕比例**（0..1，x 为水平中心、y 为垂直中心），因此每句台词可以放在不同位置，
 * 且与窗口分辨率无关；由 RPG 服务器管理员逐句指定（{@code /subtitle ui ...}）。</p>
 *
 * <p>客户端用 {@code com.vjj.client.subtitle.VjjSubtitleHud} 绘制：纯文字（可选阴影），
 * 没有原版黑框背景板。</p>
 *
 * @param text          台词文本（UTF-8，最多 1024 字节）
 * @param xFrac         水平中心（0..1，0=屏幕最左，1=最右）
 * @param yFrac         垂直中心（0..1，0=屏幕最上，1=最下）
 * @param durationTicks 显示时长 tick；0=常驻直到被 clearui 清掉
 * @param color         ARGB 颜色（如 0xFFFFFFFF 白色）
 * @param scale         字体缩放（1.0=原版大小）
 * @param shadow        是否画文字阴影
 */
public record SubtitleUiPayload(String text, float xFrac, float yFrac, int durationTicks,
                                int color, float scale, boolean shadow) implements CustomPacketPayload {
    public static final Type<SubtitleUiPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("vjjnew", "subtitle_ui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubtitleUiPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUtf(payload.text() == null ? "" : payload.text(), 1024);
                buf.writeFloat(payload.xFrac());
                buf.writeFloat(payload.yFrac());
                buf.writeVarInt(payload.durationTicks());
                buf.writeInt(payload.color());
                buf.writeFloat(payload.scale());
                buf.writeBoolean(payload.shadow());
            },
            buf -> new SubtitleUiPayload(buf.readUtf(1024), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readInt(), buf.readFloat(), buf.readBoolean()));

    @Override
    public Type<SubtitleUiPayload> type() {
        return TYPE;
    }
}
