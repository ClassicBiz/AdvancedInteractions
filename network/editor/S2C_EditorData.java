package com.luminacity.advancedinteractions.network.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Opens (or reloads) the editor with a hologram's full, unfiltered definition plus editor context
 * (templates, actions, placeholder catalogue, permissions).
 */
public record S2C_EditorData(byte[] def, String revision, CompoundTag context) {

    public static void encode(S2C_EditorData p, FriendlyByteBuf buf) {
        buf.writeByteArray(p.def);
        buf.writeUtf(p.revision, 64);
        buf.writeNbt(p.context);
    }

    public static S2C_EditorData decode(FriendlyByteBuf buf) {
        return new S2C_EditorData(buf.readByteArray(EditorNet.MAX_SAVE_BYTES), buf.readUtf(64), buf.readNbt());
    }

    public static void handle(S2C_EditorData p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.luminacity.advancedinteractions.client.editor.EditorClient.onData(p)));
        ctx.get().setPacketHandled(true);
    }
}
