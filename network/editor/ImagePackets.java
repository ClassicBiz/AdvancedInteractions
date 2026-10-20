package com.luminacity.advancedinteractions.network.editor;

import com.luminacity.advancedinteractions.images.ImageStore;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Server image transfer: request, data, and "images changed" invalidation. */
public final class ImagePackets {
    private ImagePackets() {}

    /** Names each player was already sent (one transfer per image per session; cleared on reload/logout). */
    private static final Map<UUID, Set<String>> SENT = new ConcurrentHashMap<>();
    private static final int MAX_PER_PLAYER = 256;

    public static void clearPlayer(UUID id) { SENT.remove(id); }
    public static void clearAll() { SENT.clear(); }

    /** Client asks for a server image by name. */
    public record Request(String name) {
        public static void encode(Request p, FriendlyByteBuf b) { b.writeUtf(p.name, 128); }
        public static Request decode(FriendlyByteBuf b) { return new Request(b.readUtf(128)); }

        public static void handle(Request p, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp == null) return;
                String n = ImageStore.normalize(p.name);
                if (n == null) return;
                Set<String> sent = SENT.computeIfAbsent(sp.getUUID(), k -> new HashSet<>());
                if (sent.contains(n) || sent.size() >= MAX_PER_PLAYER) return;
                sent.add(n);
                byte[] data = ImageStore.read(n);
                NetworkHandler.sendTo(sp, new Data(n, data == null ? new byte[0] : data));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** PNG bytes for a name; empty means "no such image". */
    public record Data(String name, byte[] png) {
        public static void encode(Data p, FriendlyByteBuf b) { b.writeUtf(p.name, 128); b.writeByteArray(p.png); }
        public static Data decode(FriendlyByteBuf b) { return new Data(b.readUtf(128), b.readByteArray(ImageStore.MAX_BYTES + 1024)); }

        public static void handle(Data p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.luminacity.advancedinteractions.client.ClientImages.receive(p.name, p.png)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Images changed on the server: drop cached copies and fetch again when next shown. */
    public record Invalidate() {
        public static void encode(Invalidate p, FriendlyByteBuf b) {}
        public static Invalidate decode(FriendlyByteBuf b) { return new Invalidate(); }

        public static void handle(Invalidate p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.luminacity.advancedinteractions.client.ClientImages::clear));
            ctx.get().setPacketHandled(true);
        }
    }
}
