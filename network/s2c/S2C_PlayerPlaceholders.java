package com.luminacity.advancedinteractions.network.s2c;

import com.luminacity.advancedinteractions.client.ClientPlaceholderCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Legacy packet kept for compatibility with older worlds/builds.
 *
 * New builds use {@link S2C_PlaceholderValues} which can carry any server-resolved placeholder.
 */
public class S2C_PlayerPlaceholders {

    public final String econPlayerBalance;

    public S2C_PlayerPlaceholders(String econPlayerBalance) {
        this.econPlayerBalance = econPlayerBalance == null ? "" : econPlayerBalance;
    }

    public static void encode(S2C_PlayerPlaceholders pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.econPlayerBalance, 256);
    }

    public static S2C_PlayerPlaceholders decode(FriendlyByteBuf buf) {
        String bal = buf.readUtf(256);
        return new S2C_PlayerPlaceholders(bal);
    }

    public static void handle(S2C_PlayerPlaceholders pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                Map<String, Map<String, String>> m = new HashMap<>();
                Map<String, String> inner = new HashMap<>();
                inner.put(ClientPlaceholderCache.canonical("econ", "player.balance", ""), pkt.econPlayerBalance);
                m.put(ClientPlaceholderCache.GLOBAL, inner);
                ClientPlaceholderCache.putAll(m);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
