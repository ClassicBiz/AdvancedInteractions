package com.luminacity.advancedinteractions.network.editor;

import com.luminacity.advancedinteractions.editor.EditorService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * One chunk of an edited hologram (compressed NBT split into {@link EditorNet#CHUNK_BYTES} pieces).
 * The server applies it once the last chunk arrives.
 *
 * @param baseRevision revision the editor started from; a mismatch means someone else saved meanwhile
 * @param force        overwrite even if the revision changed
 */
public record C2S_EditorSave(UUID holo, String baseRevision, boolean force, int index, int count, byte[] chunk) {

    public static void encode(C2S_EditorSave p, FriendlyByteBuf buf) {
        buf.writeUUID(p.holo);
        buf.writeUtf(p.baseRevision, 64);
        buf.writeBoolean(p.force);
        buf.writeVarInt(p.index);
        buf.writeVarInt(p.count);
        buf.writeByteArray(p.chunk);
    }

    public static C2S_EditorSave decode(FriendlyByteBuf buf) {
        return new C2S_EditorSave(buf.readUUID(), buf.readUtf(64), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readByteArray(EditorNet.CHUNK_BYTES));
    }

    public static void handle(C2S_EditorSave p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> { if (sender != null) EditorService.handleSaveChunk(sender, p); });
        ctx.get().setPacketHandled(true);
    }
}
