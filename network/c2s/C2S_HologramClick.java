
package com.luminacity.advancedinteractions.network.c2s;
import com.luminacity.advancedinteractions.interaction.ActionRunner;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.ClickSettings;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class C2S_HologramClick {
    /** Widget id used for a click on the hologram itself (meta "click.action"), not on a button. */
    public static final String PANEL_CLICK_ID = "__panel__";
    /** Meta key holding the hologram-level click action. */
    public static final String PANEL_ACTION_META = "click.action";

    private final UUID id;
    private final String actionId;
    private final String params;
    private final String widgetId;
    private final ClickSettings.Type clickType;
    /** Where on the panel the click landed (for click particles); null when unknown. */
    private final Vec3 hit;

    public C2S_HologramClick(UUID id, String actionId, String params, String widgetId, ClickSettings.Type clickType, Vec3 hit) {
        this.id = id;
        this.actionId = actionId == null ? "" : actionId;
        this.params = params == null ? "" : params;
        this.widgetId = widgetId == null ? "" : widgetId;
        this.clickType = clickType == null ? ClickSettings.Type.PRIMARY : clickType;
        this.hit = hit;
    }

    public C2S_HologramClick(UUID id, String actionId, String params, String widgetId, ClickSettings.Type clickType) {
        this(id, actionId, params, widgetId, clickType, null);
    }

    public C2S_HologramClick(UUID id, String actionId, String params, String widgetId) {
        this(id, actionId, params, widgetId, ClickSettings.Type.PRIMARY);
    }

    public C2S_HologramClick(UUID id, String actionId, String params) {
        this(id, actionId, params, "");
    }

    public C2S_HologramClick(UUID id, String actionId) {
        this(id, actionId, "");
    }

    public static void encode(C2S_HologramClick pkt, FriendlyByteBuf buf) {
        buf.writeUUID(pkt.id);
        buf.writeUtf(pkt.actionId == null ? "" : pkt.actionId);
        buf.writeUtf(pkt.params == null ? "" : pkt.params);
        buf.writeUtf(pkt.widgetId == null ? "" : pkt.widgetId);
        buf.writeByte(pkt.clickType.id());
        buf.writeBoolean(pkt.hit != null);
        if (pkt.hit != null) { buf.writeDouble(pkt.hit.x); buf.writeDouble(pkt.hit.y); buf.writeDouble(pkt.hit.z); }
    }

    public static C2S_HologramClick decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String actionId = buf.readUtf();
        String params = buf.readUtf();
        String widgetId = buf.readUtf(256);
        ClickSettings.Type type = ClickSettings.Type.byId(buf.readByte());
        Vec3 hit = buf.readBoolean() ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
        return new C2S_HologramClick(id, actionId, params, widgetId, type, hit);
    }

    /** Half the panel's world-space diagonal (blocks), from its size and scale. */
    public static double panelHalfExtent(HologramDef d) {
        int pad = d.panelStyle != null ? d.panelStyle.padding : 6;
        double pxW = Math.max(d.cols * com.luminacity.advancedinteractions.panel.PanelUnits.COL_PX, 64) + pad * 2;
        double pxH = Math.max(d.rows * com.luminacity.advancedinteractions.panel.PanelUnits.ROW_PX, 16) + pad * 2;
        double ui = d.panelStyle != null && d.panelStyle.uiScale > 0.001f ? d.panelStyle.uiScale : 1.0;
        double k = 0.025 * Math.max(0.001, d.scale) * ui;
        return 0.5 * Math.sqrt(pxW * pxW + pxH * pxH) * k;
    }

    public static void handle(C2S_HologramClick pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ServerLevel lvl = (ServerLevel) player.getLevel();

            // Entity-anchored holograms may be stored in another level's data.
            var loc = HologramSavedData.findAnywhere(lvl.getServer(), pkt.id);
            if (loc == null) return;
            HologramDef def = loc.def();

            // Validate against exactly what this player can see (views, locks, pages, menus, conditions).
            HologramDef snap = C2S_RequestSync.prepareForViewer(def, player, lvl);
            if (snap == null) return;
            if (snap.dim != null && !snap.dim.equals(lvl.dimension())) return;
            if (def.privateToEntity && def.anchorEntity != null && !def.anchorEntity.equals(player.getUUID())) return;

            // Reach: the hologram's click distance (capped by the server config). Measured to the hit
            // point when it is plausibly on the panel, otherwise to the nearest possible panel edge.
            double reach = com.luminacity.advancedinteractions.interaction.WidgetRules.clickRange(def) + 1.5;
            double half = panelHalfExtent(snap);
            Vec3 anchor = new Vec3(snap.x, snap.y, snap.z);
            Vec3 eye = player.getEyePosition();
            boolean hitOk = pkt.hit != null && pkt.hit.distanceTo(anchor) <= half + 1.0;
            double dist = hitOk ? pkt.hit.distanceTo(eye) : Math.max(0, anchor.distanceTo(eye) - half);
            if (dist > reach) return;

            // The action always comes from the server-side definition, never from the client.
            if (PANEL_CLICK_ID.equals(pkt.widgetId)) {
                if (pkt.clickType == ClickSettings.Type.LEFT) return; // panel clicks are right-click only
                String panelAction = def.meta == null ? null : def.meta.get(PANEL_ACTION_META);
                ActionRunner.clickPanel(player, lvl, def, snap, panelAction);
                return;
            }

            PanelWidget hit = null;
            for (PanelWidget w : snap.widgets) {
                if (!ClickSettings.isClickable(w)) continue;
                if (!pkt.widgetId.isEmpty()) {
                    if (pkt.widgetId.equals(w.id())) { hit = w; break; }
                } else if (w instanceof ButtonWidget bw && pkt.actionId.equals(bw.actionId())) {
                    // Older callers that only send the action id: accept it only if a visible button has it.
                    hit = w;
                    break;
                }
            }
            if (hit == null || hit instanceof com.luminacity.advancedinteractions.panel.InputWidget) return;
            // The hit point is only used for particles shown to this player.
            ActionRunner.clickWidget(player, lvl, def, snap, hit, pkt.clickType, hitOk ? pkt.hit : null);
        });
        ctx.get().setPacketHandled(true);
    }
}
