package com.luminacity.advancedinteractions.network.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Result of an editor request. {@code kind} names the request ("save", "conflict", "template",
 * "parse", "deleted", "context", ...); {@code payload} carries its data (a revision, template JSON,
 * parsed text or SNBT context).
 */
public record S2C_EditorReply(String kind, boolean ok, String message, String payload) {

    public static void encode(S2C_EditorReply p, FriendlyByteBuf buf) {
        buf.writeUtf(p.kind, 32);
        buf.writeBoolean(p.ok);
        buf.writeUtf(p.message == null ? "" : p.message, 4096);
        buf.writeUtf(p.payload == null ? "" : p.payload, 1_000_000);
    }

    public static S2C_EditorReply decode(FriendlyByteBuf buf) {
        return new S2C_EditorReply(buf.readUtf(32), buf.readBoolean(), buf.readUtf(4096), buf.readUtf(1_000_000));
    }

    public static void handle(S2C_EditorReply p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.luminacity.advancedinteractions.client.editor.EditorClient.onReply(p)));
        ctx.get().setPacketHandled(true);
    }
}
