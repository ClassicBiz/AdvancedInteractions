package com.luminacity.advancedinteractions.network.editor;

import com.luminacity.advancedinteractions.editor.EditorService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Editor operations that need the server: open, list, create, delete, duplicate, teleport,
 * template get/save/delete and placeholder parsing. See {@link EditorService#handle}.
 */
public record C2S_EditorRequest(String op, UUID holo, String a, String b, boolean flag) {

    public static C2S_EditorRequest of(String op, UUID holo) { return new C2S_EditorRequest(op, holo, "", "", false); }

    public static void encode(C2S_EditorRequest p, FriendlyByteBuf buf) {
        buf.writeUtf(p.op, 32);
        buf.writeBoolean(p.holo != null);
        if (p.holo != null) buf.writeUUID(p.holo);
        buf.writeUtf(p.a == null ? "" : p.a, 8192);
        buf.writeUtf(p.b == null ? "" : p.b, 8192);
        buf.writeBoolean(p.flag);
    }

    public static C2S_EditorRequest decode(FriendlyByteBuf buf) {
        String op = buf.readUtf(32);
        UUID holo = buf.readBoolean() ? buf.readUUID() : null;
        return new C2S_EditorRequest(op, holo, buf.readUtf(8192), buf.readUtf(8192), buf.readBoolean());
    }

    public static void handle(C2S_EditorRequest p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> { if (sender != null) EditorService.handle(sender, p); });
        ctx.get().setPacketHandled(true);
    }
}
