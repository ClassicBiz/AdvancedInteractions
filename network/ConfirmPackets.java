package com.luminacity.advancedinteractions.network;

import com.luminacity.advancedinteractions.interaction.ActionRunner;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * "Are you sure?" for elements with a confirmation message. The server asks (with a one-time token)
 * and runs the click only when the same player answers yes; every check runs again at that point.
 */
public final class ConfirmPackets {
    private ConfirmPackets() {}

    /** Server -> client: show the question. */
    public record Ask(int token, String title, String text) {
        public static void encode(Ask p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.token);
            buf.writeUtf(p.title, 256);
            buf.writeUtf(p.text, 2048);
        }

        public static Ask decode(FriendlyByteBuf buf) {
            return new Ask(buf.readVarInt(), buf.readUtf(256), buf.readUtf(2048));
        }

        public static void handle(Ask p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.luminacity.advancedinteractions.client.ConfirmClickScreen.open(p.token, p.title, p.text));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Client -> server: the answer. */
    public record Answer(int token, boolean yes) {
        public static void encode(Answer p, FriendlyByteBuf buf) {
            buf.writeVarInt(p.token);
            buf.writeBoolean(p.yes);
        }

        public static Answer decode(FriendlyByteBuf buf) {
            return new Answer(buf.readVarInt(), buf.readBoolean());
        }

        public static void handle(Answer p, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> { if (sp != null) ActionRunner.confirmed(sp, p.token, p.yes); });
            ctx.get().setPacketHandled(true);
        }
    }
}
