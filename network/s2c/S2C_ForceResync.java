package com.luminacity.advancedinteractions.network.s2c;

import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells the client to immediately request a full nearby hologram sync.
 *
 * <p>This is used as a reliable fallback for cases where the server wants a
 * view/perms/layout refresh (e.g. LuckPerms changes) but we don't want to rely
 * on movement-based autosync.</p>
 */
public class S2C_ForceResync {

    public S2C_ForceResync() {}

    public static void encode(S2C_ForceResync pkt, FriendlyByteBuf buf) {
        // empty
    }

    public static S2C_ForceResync decode(FriendlyByteBuf buf) {
        return new S2C_ForceResync();
    }

    public static void handle(S2C_ForceResync pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Ask the server for a fresh per-viewer snapshot right now.
            // (Runs on client thread; safe.)
            if (Minecraft.getInstance().player != null) {
                NetworkHandler.CHANNEL.sendToServer(new C2S_RequestSync());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
