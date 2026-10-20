package com.luminacity.advancedinteractions.network.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Hologram browser contents: every hologram (id, name, dimension, position, distance, widget count). */
public record S2C_EditorList(CompoundTag list) {

    public static void encode(S2C_EditorList p, FriendlyByteBuf buf) { buf.writeNbt(p.list); }

    public static S2C_EditorList decode(FriendlyByteBuf buf) { return new S2C_EditorList(buf.readNbt()); }

    public static void handle(S2C_EditorList p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.luminacity.advancedinteractions.client.editor.EditorClient.onList(p)));
        ctx.get().setPacketHandled(true);
    }
}
