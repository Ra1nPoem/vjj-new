package com.vjj.subtitle;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** S2C：清空该玩家 UI 屏幕上所有字幕（{@code /subtitle clearui} 用）。 */
public record SubtitleClearUiPayload() implements CustomPacketPayload {
    public static final Type<SubtitleClearUiPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("vjjnew", "subtitle_clear_ui"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubtitleClearUiPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
            },
            buf -> new SubtitleClearUiPayload());

    @Override
    public Type<SubtitleClearUiPayload> type() {
        return TYPE;
    }
}
