package com.luminacity.advancedinteractions.network.c2s;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.s2c.S2C_PlaceholderValues;
import com.luminacity.advancedinteractions.network.s2c.S2C_ForceResync;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.TextWidget;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Client -> server: "please refresh placeholder values for these holograms"
 *
 * This avoids resending the entire hologram definition when only dynamic data changed
 * (Estates/bank/perms/ownership names/balances/etc).
 */
public class C2S_RequestPlaceholderRefresh {
    private final List<UUID> holoIds;
    private static final Map<UUID, Map<UUID, Integer>> LAST_SNAPSHOT_SIG = new ConcurrentHashMap<>();

    public C2S_RequestPlaceholderRefresh(List<UUID> holoIds) {
        this.holoIds = holoIds == null ? List.of() : holoIds;
    }

    public static void encode(C2S_RequestPlaceholderRefresh pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.holoIds.size());
        for (UUID id : pkt.holoIds) buf.writeUUID(id);
    }

    public static C2S_RequestPlaceholderRefresh decode(FriendlyByteBuf buf) {
        int n = Math.min(256, buf.readVarInt()); // hard cap to prevent abuse
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readUUID());
        return new C2S_RequestPlaceholderRefresh(ids);
    }

    public static void handle(C2S_RequestPlaceholderRefresh pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sp = ctx.get().getSender();
        if (sp == null) { ctx.get().setPacketHandled(true); return; }

        ctx.get().enqueueWork(() -> {
            try {
                var lvl = sp.getLevel();
                HologramSavedData data = HologramSavedData.get(lvl);
                if (data == null) return;

                Map<String, Map<String, String>> values = new HashMap<>();
                boolean structureChanged = false;
                Map<UUID, Integer> byHolo = LAST_SNAPSHOT_SIG.computeIfAbsent(sp.getUUID(), k -> new ConcurrentHashMap<>());

                for (UUID id : pkt.holoIds) {
                    if (id == null) continue;
                    HologramDef src = data.byId(id);
                    if (src == null) {
                        // Entity-anchored holograms follow their player and may live in another level's data.
                        var loc = HologramSavedData.findAnywhere(lvl.getServer(), id);
                        if (loc == null) continue;
                        src = loc.def();
                    }
                    if (src.privateToEntity && src.anchorEntity != null && !src.anchorEntity.equals(sp.getUUID())) continue;

                    // Use the same viewer-filtering snapshot as a full sync would.
                    HologramDef snap = C2S_RequestSync.prepareForViewer(src, sp, lvl);
                    if (snap == null || snap.id == null) continue;
                    // snap.dim is the live dimension (entity anchors follow their player).
                    if (snap.dim != null && !snap.dim.equals(lvl.dimension())) continue;

                    // Detect per-viewer structural/visibility changes (typically perms/view access changes).
                    // If changed, ask the client for an immediate full sync instead of waiting for a timer.
                    int sig = snapshotSig(snap);
                    Integer prev = byHolo.put(snap.id, sig);
                    if (prev != null && prev.intValue() != sig) structureChanged = true;

                    // Collect tokens only from what the viewer can actually see, resolve non-client-only ones.
                    Map<String, String> inner = C2S_RequestSync.resolveServerTokens(snap, sp, lvl);
                    if (!inner.isEmpty()) values.put(snap.id.toString(), inner);
                }

                if (!values.isEmpty()) {
                    NetworkHandler.sendTo(sp, new S2C_PlaceholderValues(values));
                }
                if (structureChanged) {
                    NetworkHandler.sendTo(sp, new S2C_ForceResync());
                }
            } catch (Throwable t) {
                // swallow to avoid disconnects
            }
        });

        ctx.get().setPacketHandled(true);
    }

    private static Set<String> collectTokens(HologramDef d) {
        Set<String> out = new HashSet<>();
        if (d == null) return out;

        // legacy lines/buttons/apps
        if (d.lines != null) for (String s : d.lines) out.addAll(Placeholders.extractCanonicalTokens(s));
        if (d.buttons != null) for (HologramDef.ButtonDef b : d.buttons) out.addAll(Placeholders.extractCanonicalTokens(b.label));
        if (d.apps != null) for (String s : d.apps) out.addAll(Placeholders.extractCanonicalTokens(s));

        // panel widgets
        if (d.widgets != null) {
            for (var w : d.widgets) {
                if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
            }
        }

        // menus/pages
        if (d.menus != null) {
            for (var page : d.menus.values()) {
                if (page == null) continue;
                for (var w : page) {
                    if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                    else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                }
            }
        }
        return out;
    }

    private static int snapshotSig(HologramDef d) {
        int h = 1;
        h = 31 * h + Objects.hashCode(d.alias);
        h = 31 * h + Boolean.hashCode(d.privateToEntity);
        h = 31 * h + Objects.hashCode(d.dim);
        h = 31 * h + Float.floatToIntBits(d.scale);
        h = 31 * h + Double.hashCode(d.range);
        h = 31 * h + Integer.hashCode(d.rows);
        h = 31 * h + Integer.hashCode(d.cols);
        h = 31 * h + Integer.hashCode(d.lines == null ? 0 : d.lines.size());
        h = 31 * h + Integer.hashCode(d.buttons == null ? 0 : d.buttons.size());
        h = 31 * h + Integer.hashCode(d.apps == null ? 0 : d.apps.size());
        h = 31 * h + Integer.hashCode(d.widgets == null ? 0 : d.widgets.size());
        if (d.widgets != null) {
            for (var w : d.widgets) {
                if (w == null) continue;
                CompoundTag wt = new CompoundTag();
                w.saveTo(wt);
                h = 31 * h + wt.hashCode();
            }
        }
        h = 31 * h + Objects.hashCode(d.meta == null ? null : d.meta.get("ui.disabled"));
        return h;
    }

    public static void clearPlayerCache(UUID playerId) {
        if (playerId == null) return;
        LAST_SNAPSHOT_SIG.remove(playerId);
    }
}
