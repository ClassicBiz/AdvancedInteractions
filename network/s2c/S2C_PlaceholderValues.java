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
 * Server -> client: resolved placeholder values for hologram rendering.
 *
 * Payload is a map: holoId -> (canonicalPlaceholderKey -> value)
 *
 * holoId can be the empty string for global values.
 */
public class S2C_PlaceholderValues {

    public final Map<String, Map<String, String>> values;

    // Wire limits. Values longer than MAX_VALUE are truncated and over-long keys are skipped,
    // so one oversized placeholder can't make the whole packet fail to encode.
    private static final int MAX_HOLO_ID = 64;
    private static final int MAX_KEY = 256;
    private static final int MAX_VALUE = 4096;

    public S2C_PlaceholderValues(Map<String, Map<String, String>> values) {
        this.values = values == null ? new HashMap<>() : values;
    }

    public static void encode(S2C_PlaceholderValues pkt, FriendlyByteBuf buf) {
        Map<String, Map<String, String>> map = pkt.values;
        buf.writeVarInt(map.size());
        for (var e : map.entrySet()) {
            String holoId = e.getKey() == null ? "" : e.getKey();
            if (holoId.length() > MAX_HOLO_ID) holoId = holoId.substring(0, MAX_HOLO_ID);
            buf.writeUtf(holoId, MAX_HOLO_ID);

            Map<String, String> inner = e.getValue();
            if (inner == null) inner = Map.of();
            Map<String, String> safe = new HashMap<>();
            for (var kv : inner.entrySet()) {
                String k = kv.getKey() == null ? "" : kv.getKey();
                if (k.length() > MAX_KEY) continue;
                String v = kv.getValue() == null ? "" : kv.getValue();
                if (v.length() > MAX_VALUE) v = v.substring(0, MAX_VALUE);
                safe.put(k, v);
            }
            buf.writeVarInt(safe.size());
            for (var kv : safe.entrySet()) {
                buf.writeUtf(kv.getKey(), MAX_KEY);
                buf.writeUtf(kv.getValue(), MAX_VALUE);
            }
        }
    }

    public static S2C_PlaceholderValues decode(FriendlyByteBuf buf) {
        int outer = buf.readVarInt();
        Map<String, Map<String, String>> map = new HashMap<>();
        for (int i = 0; i < outer; i++) {
            String holoId = buf.readUtf(MAX_HOLO_ID);
            int innerN = buf.readVarInt();
            Map<String, String> inner = new HashMap<>();
            for (int j = 0; j < innerN; j++) {
                String k = buf.readUtf(MAX_KEY);
                String v = buf.readUtf(MAX_VALUE);
                inner.put(k, v);
            }
            map.put(holoId, inner);
        }
        return new S2C_PlaceholderValues(map);
    }

    public static void handle(S2C_PlaceholderValues pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                ClientPlaceholderCache.putAll(pkt.values);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
