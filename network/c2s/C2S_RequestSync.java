package com.luminacity.advancedinteractions.network.c2s;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HoloMenuState;
import com.luminacity.advancedinteractions.hologram.HoloPageState;
import com.luminacity.advancedinteractions.hologram.HoloViewState;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.s2c.S2C_PlaceholderValues;
import com.luminacity.advancedinteractions.network.s2c.S2C_SyncHolograms;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.panel.TextWidget;
import com.luminacity.advancedinteractions.util.ConditionEval;
import com.luminacity.advancedinteractions.util.PermUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class C2S_RequestSync {
    public C2S_RequestSync() {}
    public static C2S_RequestSync decode(FriendlyByteBuf buf) { return new C2S_RequestSync(); }
    public static void encode(C2S_RequestSync pkt, FriendlyByteBuf buf) {}

    public static void handle(C2S_RequestSync pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sp = ctx.get().getSender();
        if (sp == null) { ctx.get().setPacketHandled(true); return; }

        ctx.get().enqueueWork(() -> pushFull(sp));

        ctx.get().setPacketHandled(true);
    }

    /**
     * Server-initiated push for a single player (e.g., after permission recalculation).
     * Uses the exact same per-viewer snapshot logic as normal sync.
     */
    public static void serverPushForPlayer(ServerPlayer sp) {
        if (sp == null) return;
        pushFull(sp);
    }

    /**
     * Server-initiated push sync (used after admin edits). This avoids leaking raw defs and
     * prevents client flicker by always sending viewer-filtered snapshots (public + overlays).
     */
    public static void serverPushNearby(net.minecraft.server.level.ServerLevel lvl, com.luminacity.advancedinteractions.data.HologramSavedData data) {
        if (lvl == null) return;
        for (ServerPlayer sp : new ArrayList<>(lvl.players())) {
            if (sp == null) continue;
            pushFull(sp);
        }
    }

    /** Push fresh snapshots to every online player (entity-anchored holograms can follow players across levels). */
    public static void serverPushAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer sp : new ArrayList<>(server.getPlayerList().getPlayers())) {
            pushFull(sp);
        }
    }

    /**
     * Push one hologram to one player. This is a PARTIAL update: the client merges it with the
     * holograms it already has instead of replacing them.
     */
    public static void syncOne(ServerPlayer sp, com.luminacity.advancedinteractions.hologram.HologramDef src) {
        if (sp == null || src == null) return;
        var lvl = sp.getLevel();
        com.luminacity.advancedinteractions.hologram.HologramDef snap = prepareForViewer(src, sp, lvl);
        if (snap == null) return;
        if (!isVisibleTo(snap, sp)) return;
        sendSnapshots(sp, List.of(snap), true);
    }

    // ---------------------------------------------------------------------
    // Shared sync core
    // ---------------------------------------------------------------------

    private static void pushFull(ServerPlayer sp) {
        List<HologramDef> out = collectNearbyFor(sp);
        Set<java.util.UUID> inRange = new HashSet<>();
        for (HologramDef d : out) inRange.add(d.id);

        // Clear forced preview views when the player is no longer in range of that hologram.
        HoloViewState.clearNotInRange(sp, inRange);

        sendSnapshots(sp, out, false);
        sendGlobalPlaceholders(sp);
    }

    /**
     * Global (non-hologram) placeholder values for the client cache's fallback slot, sent via the
     * legacy S2C_PlayerPlaceholders packet. Only sent when an economy backend is registered.
     */
    public static void sendGlobalPlaceholders(ServerPlayer sp) {
        if (sp == null || Placeholders.get("econ").isEmpty()) return;
        try {
            String bal = Placeholders.resolveToken("econ.player.balance", new Placeholders.PHContext(sp.getLevel(), sp, null));
            NetworkHandler.sendTo(sp, new com.luminacity.advancedinteractions.network.s2c.S2C_PlayerPlaceholders(bal == null ? "" : bal));
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------
    // Owner view fallback (decoupled from LuminEstates)
    // ---------------------------------------------------------------------

    private static volatile String defaultOwnerToken = null;

    /**
     * Placeholder token used to find a hologram's owner when it has no "view.owner.token"/"view.owner.uuid".
     * Integrations (e.g. an estates mod) can set this; pass null to clear.
     */
    public static void setDefaultOwnerToken(String token) {
        defaultOwnerToken = (token == null || token.isBlank()) ? null : token.trim();
    }

    private static String defaultOwnerToken() {
        String t = defaultOwnerToken;
        if (t != null) return t;
        // Back-compat: the historical estate fallback, only when an "estate" namespace actually exists.
        return Placeholders.get("estate").isPresent() ? "estate.property.ownerId" : null;
    }

    /** Per-viewer snapshots of every hologram near the player (only nearby holograms keep packets small). */
    public static List<HologramDef> collectNearbyFor(ServerPlayer sp) {
        List<HologramDef> out = new ArrayList<>();
        if (sp == null) return out;
        ServerLevel lvl = sp.getLevel();
        MinecraftServer server = lvl.getServer();

        double px = sp.getX();
        double py = sp.getY();
        double pz = sp.getZ();

        for (var loc : HologramSavedData.allEverywhere(server)) {
            HologramDef d = loc.def();
            if (d == null || d.id == null) continue;

            boolean entityAnchored = d.anchor == HologramDef.AnchorType.ENTITY && d.anchorEntity != null;
            // Position-anchored holograms only come from the player's own level data.
            if (!entityAnchored && loc.level() != lvl) continue;

            Anchor a = effectiveAnchor(d, server, loc.level());
            if (a.dim() != null && !a.dim().equals(lvl.dimension())) continue;
            if (d.privateToEntity && d.anchorEntity != null && !d.anchorEntity.equals(sp.getUUID())) continue;

            // If a hologram has a larger explicit range, we respect it.
            double r = d.range > 0.0 ? d.range : 64.0;
            double max = Math.max(128.0, r + 16.0);
            double dx = a.pos().x - px;
            double dy = a.pos().y - py;
            double dz = a.pos().z - pz;
            if ((dx*dx + dy*dy + dz*dz) <= (max * max)) {
                HologramDef snap = prepareForViewer(d, sp, lvl);
                if (snap != null) out.add(snap);
            }
        }
        return out;
    }

    private static void sendSnapshots(ServerPlayer sp, List<HologramDef> snaps, boolean partial) {
        NetworkHandler.sendTo(sp, new S2C_SyncHolograms(snaps, partial));

        // Server-resolved placeholder values for the holograms we just synced.
        Level lvl = sp.getLevel();
        Map<String, Map<String, String>> values = new HashMap<>();
        for (var d : snaps) {
            if (d == null || d.id == null) continue;
            Map<String, String> inner = resolveServerTokens(d, sp, lvl);
            if (!inner.isEmpty()) values.put(d.id.toString(), inner);
        }
        if (!values.isEmpty()) {
            NetworkHandler.sendTo(sp, new S2C_PlaceholderValues(values));
        }
    }

    /** Resolve every non-client-only token used by a snapshot. */
    public static Map<String, String> resolveServerTokens(HologramDef snap, ServerPlayer sp, Level lvl) {
        Placeholders.PHContext ph = new Placeholders.PHContext(lvl, sp, snap);
        Map<String, String> inner = new HashMap<>();
        long t0 = System.nanoTime();
        java.util.UUID viewer = sp == null ? null : sp.getUUID();
        for (String canon : collectTokens(snap)) {
            if (canon == null || canon.isEmpty()) continue;
            if (isClientOnly(canon)) continue;
            // Slow sources (config "placeholderRates") are re-read at most once per their interval.
            String v = com.luminacity.advancedinteractions.interaction.PerfStats.resolve(canon, viewer, snap.id,
                    () -> Placeholders.resolveToken(canon, ph));
            inner.put(canon, v == null ? "" : v);
        }
        com.luminacity.advancedinteractions.interaction.PerfStats.record(snap.id, snap.alias, System.nanoTime() - t0, inner.size());
        return inner;
    }

    private static boolean isVisibleTo(HologramDef snap, ServerPlayer sp) {
        if (snap.dim != null && !snap.dim.equals(sp.getLevel().dimension())) return false;
        return !(snap.privateToEntity && snap.anchorEntity != null && !snap.anchorEntity.equals(sp.getUUID()));
    }

    /** Live position/dimension of a hologram (entity anchors follow their player while online). */
    public record Anchor(Vec3 pos, ResourceKey<Level> dim) {}

    public static Anchor effectiveAnchor(HologramDef d, MinecraftServer server, ServerLevel storedIn) {
        if (d.anchor == HologramDef.AnchorType.ENTITY && d.anchorEntity != null && server != null) {
            ServerPlayer anchor = server.getPlayerList().getPlayer(d.anchorEntity);
            if (anchor != null) return new Anchor(anchor.position(), anchor.getLevel().dimension());
        }
        ResourceKey<Level> dim = d.dim != null ? d.dim : (storedIn == null ? null : storedIn.dimension());
        return new Anchor(new Vec3(d.x, d.y, d.z), dim);
    }

    public static com.luminacity.advancedinteractions.hologram.HologramDef prepareForViewer(com.luminacity.advancedinteractions.hologram.HologramDef src, ServerPlayer sp, net.minecraft.world.level.Level lvl) {
        if (src == null) return null;
        // Hidden by staff: only staff still see it.
        if (src.hidden && (sp == null || !com.luminacity.advancedinteractions.editor.EditorService.canEdit(sp))) return null;

        // Decide which overlay views this player should see.
        java.util.List<String> overlayViews = selectOverlayViewsForPlayer(src, sp, lvl);

        // Pages: pages are currently only for the public/main view.
        java.util.List<com.luminacity.advancedinteractions.panel.PanelWidget> baseWidgets;
        int accessibleCount = 1;
        int viewerPage = 0;
        java.util.Set<Integer> accessiblePages = java.util.Set.of(0);
        if (src.pages != null && !src.pages.isEmpty()) {
            java.util.List<Integer> acc = new java.util.ArrayList<>(computeAccessiblePagesForViewer(src, sp, lvl, overlayViews));
            java.util.Collections.sort(acc);
            accessibleCount = acc.size();
            accessiblePages = new java.util.HashSet<>(acc);

            int requested = HoloPageState.get(sp, src);
            int pageIdx = acc.contains(requested) ? requested : acc.get(0);
            if (pageIdx != requested) HoloPageState.set(sp, src, pageIdx);
            viewerPage = pageIdx;

            baseWidgets = src.widgetsForPage(pageIdx);
        } else {
            baseWidgets = src.widgets;
        }

        // An open menu (advholo:menu_open) replaces the page content for this viewer.
        String openMenu = HoloMenuState.get(sp, src);
        java.util.List<PanelWidget> menuWidgets = src.widgetsForMenu(openMenu);
        if (menuWidgets != null) baseWidgets = menuWidgets;

        // Combine public/page widgets with overlay view widgets.
        java.util.List<com.luminacity.advancedinteractions.panel.PanelWidget> widgets = new java.util.ArrayList<>();
        java.util.Set<String> activeViews = new java.util.HashSet<>();
        activeViews.add("public");
        if (overlayViews != null) {
            for (String v : overlayViews) {
                if (v == null) continue;
                String low = v.toLowerCase(java.util.Locale.ROOT).trim();
                if (!low.isEmpty()) activeViews.add(low);
            }
        }

        // Legacy line/button holograms (pre-panel saves): render them as widgets so they still show and click.
        widgets.addAll(legacyWidgets(src));

        if (baseWidgets != null) widgets.addAll(baseWidgets);

        // Add widgets from views the player can access (normal overlays).
        if (overlayViews != null && src.views != null) {
            for (String v : overlayViews) {
                if (v == null) continue;
                String low = v.toLowerCase(java.util.Locale.ROOT).trim();
                if (low.isEmpty() || low.equals("public") || low.equals("main")) continue;
                // Views with per-page content contribute that page's widgets; others overlay every page.
                java.util.List<com.luminacity.advancedinteractions.panel.PanelWidget> extra = src.viewWidgetsForPage(low, viewerPage);
                if (extra != null && !extra.isEmpty()) widgets.addAll(extra);
            }
        }

        // Shared widgets: allow a widget defined in one view to also appear when another view is active.
        // Example: a widget lives in "test" but you /show <id> to admin => it should render when "admin" is active.
        if (src.widgetShowToViews != null && !src.widgetShowToViews.isEmpty() && src.views != null && !activeViews.isEmpty()) {
            // For stable ordering, append shared widgets grouped by active view order (so "admin" shared items appear above "owner", etc).
            java.util.List<String> order = new java.util.ArrayList<>(activeViews);
            // Make sure public is first (though sharing to public is rare).
            order.remove("public");
            java.util.Collections.sort(order);
            order.add(0, "public");

            java.util.Set<String> added = new java.util.HashSet<>();
            for (var w0 : widgets) {
                if (w0 != null && w0.id() != null) added.add(w0.id());
            }

            for (String activeView : order) {
                if (activeView == null || activeView.isBlank()) continue;

                for (var e : src.views.entrySet()) {
                    String sourceView = e.getKey();
                    if (sourceView == null) continue;
                    String sourceLow = sourceView.toLowerCase(java.util.Locale.ROOT).trim();
                    // If the source view is already active, its widgets are already included.
                    if (activeViews.contains(sourceLow)) continue;

                    java.util.List<com.luminacity.advancedinteractions.panel.PanelWidget> list = e.getValue();
                    if (list == null || list.isEmpty()) continue;

                    for (var w : list) {
                        if (w == null) continue;
                        String wid = w.id();
                        if (wid == null || wid.isBlank()) continue;

                        java.util.Set<String> showTo = src.widgetShowToViews.get(wid);
                        if (showTo == null || showTo.isEmpty()) continue;
                        if (!showTo.contains(activeView)) continue;

                        if (!added.contains(wid)) {
                            widgets.add(w);
                            added.add(wid);
                        }
                    }
                }
            }
        }

        // Build a lightweight copy for the sync packet so we can safely swap widget lists per viewer.
        var d = new com.luminacity.advancedinteractions.hologram.HologramDef();
        d.id = src.id;
        d.alias = src.alias;
        d.privateToEntity = src.privateToEntity;
        d.dim = src.dim;
        d.billboard = src.billboard;
        d.fixedYawDeg = src.fixedYawDeg;
        d.anchor = src.anchor;
        d.anchorEntity = src.anchorEntity;
        d.x = src.x; d.y = src.y; d.z = src.z;
        d.offX = src.offX; d.offY = src.offY; d.offZ = src.offZ;
        d.scale = src.scale;
        d.range = src.range;
        d.boxWidth = src.boxWidth;
        d.boxHeight = src.boxHeight;
        d.rows = src.rows;
        d.cols = src.cols;
        d.panelStyle = src.panelStyle;
        d.templateId = src.templateId;
        d.binding = src.binding;
        d.links = src.links == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(src.links);
        d.owner = src.owner;
        d.locked = src.locked;

        // Entity-anchored: send the live position/dimension so range checks and the client fallback are correct.
        MinecraftServer server = sp != null ? sp.getServer() : (lvl == null ? null : lvl.getServer());
        if (server != null) {
            Anchor a = effectiveAnchor(src, server, lvl instanceof ServerLevel sl ? sl : null);
            d.x = a.pos().x; d.y = a.pos().y; d.z = a.pos().z;
            if (a.dim() != null) d.dim = a.dim();
        }

        if (src.lines != null) d.lines = new java.util.ArrayList<>(src.lines);
        if (src.buttons != null) d.buttons = new java.util.ArrayList<>(src.buttons);
        if (src.apps != null) d.apps = new java.util.ArrayList<>(src.apps);

        d.widgets = new java.util.ArrayList<>();
        StringBuilder disabled = new StringBuilder();
        if (widgets != null) {
            // Hide page navigation buttons unless the player can access 2+ pages.
            boolean hideNav = accessibleCount < 2;
            for (var w : widgets) {
                if (w == null) continue;

                // Widget-level visibility lock (perm/op)
                if (!canViewWidget(sp, w)) continue;

                // View overlay visibility (hide/show/only)
                if (!isWidgetVisibleForViews(src, w, overlayViews)) continue;

                // Live visibility condition (visibleIf)
                if (!ConditionEval.test(w.condition(), src, sp, lvl)) continue;

                // Start-hidden elements and per-player show/hide (advholo:show / advholo:hide)
                if (!com.luminacity.advancedinteractions.interaction.WidgetRules.visibleByToggle(sp, src, w)) continue;
                // Clickables the player may not use, when set to hide rather than grey out
                if (com.luminacity.advancedinteractions.interaction.WidgetRules.hiddenAsUnusable(sp, src, w, lvl)) continue;

                if (w instanceof com.luminacity.advancedinteractions.panel.ButtonWidget bw) {
                    String aid = bw.actionId();
                    if (aid != null) {
                        String low = aid.toLowerCase(java.util.Locale.ROOT);
                        if (low.startsWith("advholo:page_")) {
                            if (hideNav) continue;
                            // Hide page_set buttons that target pages the player cannot access.
                            if (low.startsWith("advholo:page_set")) {
                                Integer target = parsePageSetTarget(aid);
                                if (target != null) {
                                    int idx0 = Math.max(0, target - 1); // action param is 1-based
                                    if (!accessiblePages.contains(idx0)) continue;
                                }
                            }
                        }
                    }
                }
                if (com.luminacity.advancedinteractions.panel.ClickSettings.isClickable(w)
                        && com.luminacity.advancedinteractions.interaction.WidgetRules.isDisabled(sp, src, w, lvl)) {
                    if (disabled.length() > 0) disabled.append(',');
                    disabled.append(w.id());
                }
                d.widgets.add(w);
            }
        }

        d.meta = new java.util.HashMap<>();
        if (src.meta != null) d.meta.putAll(src.meta);
        // Elements shown greyed out for this viewer (the server still refuses their clicks).
        if (disabled.length() > 0) d.meta.put("ui.disabled", disabled.toString());
        // Effective click distance, so the client only offers clicks the server will accept.
        d.meta.put("click.range", Double.toString(com.luminacity.advancedinteractions.interaction.WidgetRules.clickRange(src)));
        d.clickRange = src.clickRange;
        d.range = Math.min(d.range, com.luminacity.advancedinteractions.config.AIConfig.maxViewDistance());

        return d;
    }

    /** Synthetic widgets for holograms that only have legacy lines/buttons (no panel content at all). */
    private static List<PanelWidget> legacyWidgets(HologramDef src) {
        List<PanelWidget> out = new ArrayList<>();
        boolean hasLegacy = (src.lines != null && !src.lines.isEmpty()) || (src.buttons != null && !src.buttons.isEmpty());
        if (!hasLegacy) return out;
        boolean hasPanel = (src.widgets != null && !src.widgets.isEmpty())
                || (src.pages != null && !src.pages.isEmpty())
                || (src.views != null && !src.views.isEmpty())
                || (src.menus != null && !src.menus.isEmpty());
        if (hasPanel) return out;

        double y = 2;
        if (src.lines != null) {
            for (int i = 0; i < src.lines.size(); i++) {
                out.add(new TextWidget("legacy_line_" + i, 4, y, src.lines.get(i) == null ? "" : src.lines.get(i)));
                y += 11;
            }
        }
        if (src.buttons != null) {
            for (int i = 0; i < src.buttons.size(); i++) {
                HologramDef.ButtonDef b = src.buttons.get(i);
                if (b == null) continue;
                out.add(new ButtonWidget("legacy_btn_" + i, 4, y, b.id == null ? "" : b.id, b.label == null ? "" : b.label));
                y += 16;
            }
        }
        return out;
    }

    public static boolean canAccessView(com.luminacity.advancedinteractions.hologram.HologramDef d, String viewId, ServerPlayer sp, net.minecraft.world.level.Level lvl) {
        if (viewId == null || viewId.isBlank()) return true;
        String v = viewId.toLowerCase(java.util.Locale.ROOT).trim();
        if (v.equals("public") || v.equals("main")) return true;
        if (sp == null) return false;

        // Generic explicit viewer binding for any view id:
        //   view.<id>.uuid  -> exact UUID match
        //   view.<id>.token -> placeholder token that resolves to viewer UUID
        // This lets integrations define role-specific views (e.g. renter) without patching AI again.
        try {
            String explicit = d.meta == null ? null : d.meta.get("view." + v + ".uuid");
            if (explicit != null && !explicit.isBlank()) {
                if (sp.getUUID().toString().equalsIgnoreCase(explicit.trim())) return true;
            }
        } catch (Exception ignored) {}
        try {
            String token = d.meta == null ? null : d.meta.get("view." + v + ".token");
            if (token != null && !token.isBlank()) {
                var ph = new Placeholders.PHContext(lvl, sp, d);
                String viewerId = Placeholders.resolveToken(token, ph);
                if (viewerId != null && !viewerId.isBlank()
                        && sp.getUUID().toString().equalsIgnoreCase(viewerId.trim())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // owner binding
        if (v.equals("owner")) {
            try {
                String explicit = d.meta == null ? null : d.meta.get("view.owner.uuid");
                if (explicit != null && !explicit.isBlank()) {
                    if (sp.getUUID().toString().equalsIgnoreCase(explicit.trim())) return true;
                }
            } catch (Exception ignored) {}

            try {
                String token = d.meta == null ? null : d.meta.get("view.owner.token");
                if (token == null || token.isBlank()) token = defaultOwnerToken();
                var ph = new Placeholders.PHContext(lvl, sp, d);
                String ownerId = token == null ? null : Placeholders.resolveToken(token, ph);
                if (ownerId != null && !ownerId.isBlank()) {
                    if (sp.getUUID().toString().equalsIgnoreCase(ownerId.trim())) return true;
                }
            } catch (Exception ignored) {}
        }

        // View lock (perm/op) stored in def.viewLocks.
        // Back-compat: older saves stored view locks in meta under:
        //   view.<id>.perm / view.<id>.op (or view.<id>.oplevel)
        com.luminacity.advancedinteractions.hologram.HologramDef.PageLock lock = null;
        try { lock = d.getViewLock(v); } catch (Throwable ignored) {}

        String perm = (lock == null ? null : lock.perm);
        int opLevel = (lock == null ? -1 : lock.opLevel);

        // Legacy meta fallback if no explicit lock configured
        if ((perm == null || perm.isBlank()) && opLevel < 0 && d.meta != null) {
            try {
                String p = d.meta.get("view." + v + ".perm");
                if (p != null && !p.isBlank()) perm = p;
            } catch (Throwable ignored) {}
            try {
                String opStr = d.meta.get("view." + v + ".op");
                if (opStr == null) opStr = d.meta.get("view." + v + ".oplevel");
                if (opStr == null && v.equals("staff")) opStr = d.meta.get("view.staff.op");
                if (opStr != null && !opStr.isBlank()) {
                    try { opLevel = Integer.parseInt(opStr.trim()); } catch (NumberFormatException ignored2) {}
                }
            } catch (Throwable ignored) {}
        }

        // Op-level lock: either the perm node or the op level may grant access.
        if (opLevel >= 0 && sp.hasPermissions(Math.min(4, opLevel))) return true;

        // Optional secondary permission node (e.g., global + per-holo admin).
        String permExtra = null;
        if (d.meta != null) {
            try {
                String pe = d.meta.get("view." + v + ".perm.extra");
                if (pe != null && !pe.isBlank()) permExtra = pe;
            } catch (Throwable ignored) {}
        }

        // Fallback defaults if integration didn't explicitly configure lock metadata.
        if ((perm == null || perm.isBlank()) && (permExtra == null || permExtra.isBlank())) {
            String aliasToken = "unknown";
            try {
                String a = d.alias;
                if (a != null && !a.isBlank()) {
                    aliasToken = a.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "_");
                }
            } catch (Throwable ignored) {}
            if ("admin".equals(v)) {
                perm = "adv.holo.view.admin";
                permExtra = "adv.holo." + aliasToken + ".view.admin";
            } else {
                perm = "adv.holo." + aliasToken + ".view." + v;
            }
        }

        // Perm nodes (without LuckPerms installed, perm-only locks fall back to op level 2).
        if (perm != null && !perm.isBlank()) {
            if (PermUtil.check(sp, perm.trim(), -1)) return true;
        }
        if (permExtra != null && !permExtra.isBlank()) {
            if (PermUtil.check(sp, permExtra.trim(), -1)) return true;
        }

        return false;
    }

    private static boolean canViewWidget(ServerPlayer sp, PanelWidget w) {
        if (w == null) return false;
        // Either the perm node or the op level may grant visibility.
        return PermUtil.check(sp, w.visPerm(), w.visOp());
    }

    private static java.util.Set<Integer> computeAccessiblePagesForViewer(
            com.luminacity.advancedinteractions.hologram.HologramDef src,
            ServerPlayer sp,
            net.minecraft.world.level.Level lvl,
            java.util.List<String> overlayViews
    ) {
        java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
        if (src == null || sp == null) {
            out.add(0);
            return out;
        }

        java.util.Set<String> activeViews = new java.util.HashSet<>();
        activeViews.add("public");
        if (overlayViews != null) {
            for (String v : overlayViews) {
                if (v == null) continue;
                String low = v.toLowerCase(java.util.Locale.ROOT).trim();
                if (!low.isEmpty()) activeViews.add(low);
            }
        }

        int count = Math.max(1, src.pageCount());
        for (int i = 0; i < count; i++) {
            String bound = src.getPageViewBinding(i);
            String b = bound == null ? "" : bound.toLowerCase(java.util.Locale.ROOT).trim();
            boolean viewBound = !b.isEmpty() && !b.equals("public") && !b.equals("main");

            // A forced preview of the bound view lets staff inspect the page before perms are wired.
            if (viewBound && activeViews.contains(b)) {
                var lock = src.getPageLock(i);
                if (lock == null || lock.isEmpty() || PermUtil.check(sp, lock.perm, lock.opLevel)) out.add(i);
                continue;
            }

            if (src.canViewPage(sp, i)) out.add(i);
        }

        if (out.isEmpty()) out.add(0);
        return out;
    }

    private static Integer parsePageSetTarget(String actionId) {
        if (actionId == null) return null;
        int q = actionId.indexOf('?');
        if (q < 0) return null;
        String qs = actionId.substring(q + 1);
        // very small query parser: page=2 / p=2 / i=2
        for (String part : com.luminacity.advancedinteractions.util.QueryParams.split(qs)) {
            if (part == null || part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
            if (!(k.equals("page") || k.equals("p") || k.equals("i"))) continue;
            String v = part.substring(eq + 1).trim();
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /**
     * Determine which overlay views should be rendered on top of the public/page widgets.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>Public widgets are always rendered.</li>
     *   <li>If a preview view is forced via /holo ... view switch, ONLY that view overlays (no others).</li>
     *   <li>Otherwise, all accessible non-public views overlay. Priority views are applied last.</li>
     * </ul>
     */
    private static java.util.List<String> selectOverlayViewsForPlayer(com.luminacity.advancedinteractions.hologram.HologramDef d, ServerPlayer sp, net.minecraft.world.level.Level lvl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (d == null || sp == null) return out;

        // Forced preview is an admin/testing tool: if set, ONLY that view overlays.
// This intentionally bypasses locks so staff can preview a view before wiring perms.
        String forced = HoloViewState.get(sp, d);
        if (forced != null && !forced.isBlank()) {
            String f = forced.toLowerCase(java.util.Locale.ROOT).trim();
            if (!f.isEmpty() && !f.equals("public") && !f.equals("main")) {
                // Must exist
                if (d.views != null && d.views.containsKey(f)) out.add(f);
            }
            return out;
        }

        if (d.views == null || d.views.isEmpty()) return out;

        // First add non-priority views in stable alphabetical order.
        java.util.List<String> keys = new java.util.ArrayList<>(d.views.keySet());
        java.util.Collections.sort(keys);
        for (String v : keys) {
            if (v == null) continue;
            String low = v.toLowerCase(java.util.Locale.ROOT).trim();
            if (low.isEmpty() || low.equals("public") || low.equals("main")) continue;
            if (low.equals("admin") || low.equals("owner") || low.equals("staff")) continue;
            if (canAccessView(d, low, sp, lvl)) out.add(low);
        }

        // Then add priority views last, so their widgets naturally render above others at equal layer.
        String[] priority = new String[] { "staff", "owner", "admin" };
        for (String v : priority) {
            if (d.views.containsKey(v) && canAccessView(d, v, sp, lvl)) out.add(v);
        }

        return out;
    }

    private static boolean isWidgetVisibleForViews(com.luminacity.advancedinteractions.hologram.HologramDef src, com.luminacity.advancedinteractions.panel.PanelWidget w, java.util.List<String> overlayViews) {
        if (src == null || w == null) return true;
        String id = w.id();
        if (id == null || id.isBlank()) return true;

        // Normalize active views set (public is always implicitly active)
        java.util.Set<String> active = new java.util.HashSet<>();
        active.add("public");
        if (overlayViews != null) {
            for (String v : overlayViews) {
                if (v == null) continue;
                String low = v.toLowerCase(java.util.Locale.ROOT).trim();
                if (!low.isEmpty()) active.add(low);
            }
        }

        // only-view mapping
        if (src.widgetOnlyView != null && !src.widgetOnlyView.isEmpty()) {
            String only = src.widgetOnlyView.get(id);
            if (only != null && !only.isBlank()) {
                String ov = only.toLowerCase(java.util.Locale.ROOT).trim();
                return active.contains(ov);
            }
        }

        // hidden in any active overlay view
        if (src.viewHiddenWidgets != null && !src.viewHiddenWidgets.isEmpty() && !active.isEmpty()) {
            for (String v : active) {
                java.util.Set<String> hidden = src.viewHiddenWidgets.get(v);
                if (hidden != null && hidden.contains(id)) return false;
            }
        }

        return true;
    }

    /** Each provider declares which of its keys the client resolves itself (Placeholders.PlaceholderProvider#isClientSide). */
    public static boolean isClientOnly(String canonical) {
        return Placeholders.isClientResolvable(canonical);
    }

    public static Set<String> collectTokens(com.luminacity.advancedinteractions.hologram.HologramDef d) {
        Set<String> out = new HashSet<>();

        // Legacy lines/buttons
        if (d.lines != null) {
            for (String s : d.lines) out.addAll(Placeholders.extractCanonicalTokens(s));
        }
        if (d.buttons != null) {
            for (var b : d.buttons) {
                if (b == null) continue;
                out.addAll(Placeholders.extractCanonicalTokens(b.label));
                // NOTE: actionId placeholders are resolved on click server-side (no need to sync).
            }
        }

        // Current widgets (main)
        if (d.widgets != null) {
            for (var w : d.widgets) {
                // Hover tooltips can use placeholders too.
                var cs = w.interaction();
                if (cs != null && !cs.tooltip.isBlank()) out.addAll(Placeholders.extractCanonicalTokens(cs.tooltip));
                if (w instanceof com.luminacity.advancedinteractions.panel.InputWidget in) {
                    out.add(Placeholders.canonicalize("input." + in.name()));
                    out.addAll(Placeholders.extractCanonicalTokens(in.label()));
                    out.addAll(Placeholders.extractCanonicalTokens(in.min()));
                    out.addAll(Placeholders.extractCanonicalTokens(in.max()));
                }
                if (w instanceof TextWidget tw) {
                    out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                } else if (w instanceof ButtonWidget bw) {
                    out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                } else if (w instanceof com.luminacity.advancedinteractions.panel.ChartWidget cw) {
                    out.add("chart.data:" + cw.id()); // the sampled history, see ChartSampler
                } else if (w instanceof com.luminacity.advancedinteractions.panel.ImageWidget iw) {
                    out.addAll(Placeholders.extractCanonicalTokens(iw.source()));
                }
            }
        }

        // Pages
        if (d.pages != null) {
            for (var page : d.pages) {
                if (page == null) continue;
                for (var w : page) {
                    if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                    else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                }
            }
        }

        // Menus
        if (d.menus != null) {
            for (var page : d.menus.values()) {
                if (page == null) continue;
                for (var w : page) {
                    if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                    else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                }
            }
        }

        // View overlays
        if (d.views != null) {
            for (Object obj : d.views.values()) {
                if (obj == null) continue;

                if (obj instanceof Iterable<?> it) {
                    for (Object wObj : it) {
                        if (wObj instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                        else if (wObj instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                    }
                } else if (obj instanceof PanelWidget w) {
                    // Fallback if someone ever stored a single widget instead of a list
                    if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                    else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                }
            }
        }

        // Per-view pages
        if (d.viewPages != null) {
            for (var pagesOfView : d.viewPages.values()) {
                if (pagesOfView == null) continue;
                for (var page : pagesOfView) {
                    if (page == null) continue;
                    for (var w : page) {
                        if (w instanceof TextWidget tw) out.addAll(Placeholders.extractCanonicalTokens(tw.text()));
                        else if (w instanceof ButtonWidget bw) out.addAll(Placeholders.extractCanonicalTokens(bw.label()));
                    }
                }
            }
        }

        return out;
    }
}
