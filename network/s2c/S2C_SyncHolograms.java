
package com.luminacity.advancedinteractions.network.s2c;

import com.luminacity.advancedinteractions.client.HologramClientManager;
import com.luminacity.advancedinteractions.hologram.ClientHologram;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.panel.ShapeWidget;
import com.luminacity.advancedinteractions.panel.PanelStyle;
import com.luminacity.advancedinteractions.panel.TextWidget;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import net.minecraft.nbt.CompoundTag;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class S2C_SyncHolograms {
    private static final int MAGIC = 0xA11E; // packet marker to avoid count/schema collisions
    private static final int SCHEMA = 4; // 4: partial (merge) flag
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int saneCount(int v, int max, String what) {
        if (v < 0 || v > max) throw new IllegalStateException("Bad " + what + " count: " + v);
        return v;
    }

    public final List<HologramDef> defs;

    /**
     * false = full nearby snapshot (client replaces everything it has),
     * true  = partial update (client merges these holograms into what it has).
     */
    public final boolean partial;

    public S2C_SyncHolograms(List<HologramDef> defs) {
        this(defs, false);
    }

    public S2C_SyncHolograms(List<HologramDef> defs, boolean partial) {
        this.defs = defs;
        this.partial = partial;
    }

    /**
     * Meta keys that only matter server-side (lock perms, viewer bindings) and must not reach clients.
     */
    public static boolean isServerOnlyMetaKey(String key) {
        if (key == null) return true;
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (!k.startsWith("view.")) return false;
        return k.endsWith(".perm") || k.endsWith(".perm.extra") || k.endsWith(".op") || k.endsWith(".oplevel")
                || k.endsWith(".uuid") || k.endsWith(".token");
    }

   public static void encode(S2C_SyncHolograms pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(MAGIC);
        buf.writeVarInt(SCHEMA);
        buf.writeBoolean(pkt.partial);
        buf.writeVarInt(pkt.defs.size());
        for (HologramDef d : pkt.defs) {
            buf.writeUUID(d.id);
            buf.writeUtf(d.alias == null ? "" : d.alias);
            buf.writeBoolean(d.privateToEntity);
            // dimension
            ResourceLocation dim = d.dim == null ? Level.OVERWORLD.location() : d.dim.location();
            buf.writeResourceLocation(dim);
            buf.writeBoolean(d.billboard);
            buf.writeFloat(d.fixedYawDeg);
            buf.writeEnum(d.anchor);
            buf.writeNullable(d.anchorEntity, FriendlyByteBuf::writeUUID);
            buf.writeDouble(d.x); buf.writeDouble(d.y); buf.writeDouble(d.z);
            buf.writeDouble(d.offX); buf.writeDouble(d.offY); buf.writeDouble(d.offZ);
            buf.writeFloat(d.scale);
            buf.writeDouble(d.range);
            buf.writeDouble(d.boxWidth); buf.writeDouble(d.boxHeight);

            // lines
            buf.writeVarInt(d.lines.size());
            for (var s : d.lines) buf.writeUtf(s == null ? "" : s);

            // buttons
            buf.writeVarInt(d.buttons.size());
            for (var b : d.buttons) {
                buf.writeUtf(b == null || b.id == null ? "" : b.id);
                buf.writeUtf(b == null || b.label == null ? "" : b.label);
            }

            // apps
            buf.writeVarInt(d.apps.size());
            for (var a : d.apps) buf.writeUtf(a == null ? "" : a);

            // panel attrs + widgets (added)
            buf.writeVarInt(Math.max(1, d.rows));
            buf.writeVarInt(Math.max(1, d.cols));
            CompoundTag style = new CompoundTag();
            if (d.panelStyle != null) d.panelStyle.saveTo(style);
            buf.writeNbt(style);
            buf.writeVarInt(d.widgets.size());
            for (PanelWidget w : d.widgets) {
                CompoundTag wt = new CompoundTag();
                w.saveTo(wt);
                buf.writeNbt(wt);
            }

            // meta map (custom key->value)
            CompoundTag meta = new CompoundTag();
            if (d.meta != null) {
                for (var e : d.meta.entrySet()) {
                    if (e.getKey() == null || e.getKey().isEmpty()) continue;
                    if (isServerOnlyMetaKey(e.getKey())) continue;
                    meta.putString(e.getKey(), e.getValue() == null ? "" : e.getValue());
                }
            }
            buf.writeNbt(meta);
        }
    }


    public static S2C_SyncHolograms decode(FriendlyByteBuf buf) {
        int first = buf.readVarInt();
        int schema;
        int n;
        boolean partial = false;
        if (first == MAGIC) {
            schema = buf.readVarInt();
            if (schema >= 4) partial = buf.readBoolean();
            n = buf.readVarInt();
        } else {
            // Back-compat: older builds wrote only the hologram count first.
            schema = 1;
            n = first;
        }
        n = saneCount(n, 2048, "hologram");
        List<HologramDef> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            String alias = schema >= 3 ? buf.readUtf() : "";
            boolean privateToEntity = schema >= 3 && buf.readBoolean();
            ResourceKey<Level> dimKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, buf.readResourceLocation());
            boolean billboard = buf.readBoolean();
            float fixedYawDeg = buf.readFloat();
            HologramDef.AnchorType anchor = buf.readEnum(HologramDef.AnchorType.class);
            UUID anchorEntity = buf.readNullable(FriendlyByteBuf::readUUID);
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            double ox = buf.readDouble(), oy = buf.readDouble(), oz = buf.readDouble();
            float scale = buf.readFloat();
            double range = buf.readDouble();
            double bw = buf.readDouble(), bh = buf.readDouble();

            int lc = saneCount(buf.readVarInt(), 1024, "line");
            List<String> lines = new ArrayList<>(lc);
            for (int j = 0; j < lc; j++) lines.add(buf.readUtf());

            int bc = saneCount(buf.readVarInt(), 1024, "button");
            List<HologramDef.ButtonDef> btns = new ArrayList<>(bc);
            for (int j = 0; j < bc; j++) btns.add(new HologramDef.ButtonDef(buf.readUtf(), buf.readUtf()));

            int ac = saneCount(buf.readVarInt(), 1024, "app");
            List<String> apps = new ArrayList<>(ac);
            for (int j = 0; j < ac; j++) apps.add(buf.readUtf());

            // panel attrs + widgets (added)
            int rows = buf.readVarInt();
            int cols = buf.readVarInt();
            CompoundTag styleTag = buf.readNbt();
            PanelStyle style = PanelStyle.load(styleTag == null ? new CompoundTag() : styleTag);
            int wc = saneCount(buf.readVarInt(), 4096, "widget");
            java.util.List<PanelWidget> widgets = new java.util.ArrayList<>(wc);
            for (int j = 0; j < wc; j++) {
                CompoundTag wt = buf.readNbt();
                if (wt == null) continue;
                String kind = wt.getString("kind");
                PanelWidget w = "text".equals(kind) ? new TextWidget()
                                : "button".equals(kind) ? new ButtonWidget()
                                : "shape".equals(kind) ? new ShapeWidget()
                                : com.luminacity.advancedinteractions.panel.Widgets.create(kind);
                if (w != null) { w.loadFrom(wt); widgets.add(w); }
            }

            // meta map
            CompoundTag meta = schema >= 3 ? buf.readNbt() : null;

            // build def
            HologramDef d = new HologramDef();
            d.id = id;
            d.alias = alias;
            d.privateToEntity = privateToEntity;
            d.dim = dimKey;
            d.billboard = billboard;
            d.fixedYawDeg = fixedYawDeg;
            d.anchor = anchor;
            d.anchorEntity = anchorEntity;
            d.x = x; d.y = y; d.z = z;
            d.offX = ox; d.offY = oy; d.offZ = oz;
            d.scale = scale;
            d.rows = rows; d.cols = cols; d.panelStyle = style;
            d.widgets.clear(); d.widgets.addAll(widgets);
            d.range = range;
            d.boxWidth = bw; d.boxHeight = bh;
            d.lines.clear(); d.lines.addAll(lines);
            d.buttons.clear(); d.buttons.addAll(btns);
            d.apps.clear(); d.apps.addAll(apps);

            if (meta != null) {
                for (String k : meta.getAllKeys()) {
                    d.meta.put(k, meta.getString(k));
                }
            }

            list.add(d);
        }
        return new S2C_SyncHolograms(list, partial);
    }


    public static void handle(S2C_SyncHolograms pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                List<ClientHologram> client = new ArrayList<>(pkt.defs.size());
                for (HologramDef d : pkt.defs) {
                List<ClientHologram.ClientHologramButton> btns = new ArrayList<>(d.buttons.size());
                for (HologramDef.ButtonDef b : d.buttons) btns.add(new ClientHologram.ClientHologramButton(b.id, b.label));
                client.add(new ClientHologram(
                        d.id, d.alias, java.util.Map.copyOf(d.meta), d.billboard, d.fixedYawDeg, d.anchor, d.anchorEntity,
                        new net.minecraft.world.phys.Vec3(d.offX, d.offY, d.offZ),
                        d.privateToEntity, d.dim, new net.minecraft.world.phys.Vec3(d.x, d.y, d.z),
                        new ArrayList<>(d.lines), btns, new ArrayList<>(d.apps),
                        d.scale, d.range, d.boxWidth, d.boxHeight,
                        Math.max(1, d.rows), Math.max(1, d.cols), d.panelStyle, new java.util.ArrayList<>(d.widgets)
                ));
            }
                if (pkt.partial) {
                    for (ClientHologram h : client) HologramClientManager.upsert(h);
                } else {
                    HologramClientManager.replaceAll(client);
                }
            } catch (Throwable t) {
                LOGGER.error("Failed applying hologram sync packet; ignoring to avoid disconnect", t);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2C_SyncHolograms pkt) {
        HologramClientManager.clear();
        for (HologramDef d : pkt.defs) {
            List<ClientHologram.ClientHologramButton> btns = new ArrayList<>();
            for (HologramDef.ButtonDef b : d.buttons) {
                btns.add(new ClientHologram.ClientHologramButton(b.id, b.label));
            }
            ClientHologram c = new ClientHologram(
                d.id, d.alias, java.util.Map.copyOf(d.meta), d.billboard, d.fixedYawDeg, d.anchor, d.anchorEntity,
                new Vec3(d.offX, d.offY, d.offZ), d.privateToEntity,
                d.dim, new Vec3(d.x, d.y, d.z), d.lines, btns, d.apps,
                d.scale, d.range, d.boxWidth, d.boxHeight,
                Math.max(1, d.rows), Math.max(1, d.cols),
                d.panelStyle, new java.util.ArrayList<>(d.widgets)
            );
            HologramClientManager.upsert(c);
        }
    }
}
