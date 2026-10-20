package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HoloMenuState;
import com.luminacity.advancedinteractions.hologram.HoloPageState;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Built-in {holo.*} placeholder provider. */
public final class HoloPlaceholderProvider implements Placeholders.PlaceholderProvider {

    /** Per-viewer page/menu state lives on the server; the rest is in the synced definition. */
    private static final Set<String> CLIENT_KEYS = Set.of("id", "alias", "x", "y", "z", "dim");

    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("id", "Hologram UUID");
        KEYS.put("alias", "Hologram name");
        KEYS.put("x", "Position X (also y, z)");
        KEYS.put("dim", "Dimension id");
        KEYS.put("page", "Viewer's current page number, counting only pages they can open");
        KEYS.put("pages", "Number of pages this viewer can open (\"Page {holo.page}/{holo.pages}\")");
        KEYS.put("page.title", "Title of the viewer's current page");
        KEYS.put("menu", "Name of the menu the viewer has open, empty if none");
    }

    @Override public boolean isClientSide(String key) { return CLIENT_KEYS.contains(key); }

    @Override public Map<String, String> keys() { return KEYS; }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null) return "";
        HologramDef h = ctx.holo();
        if (h == null) return "";

        key = key == null ? "" : key.toLowerCase();

        return switch (key) {
            case "id" -> h.id == null ? "" : h.id.toString();
            case "alias" -> h.alias == null ? "" : h.alias;
            case "x" -> Double.toString(h.x);
            case "y" -> Double.toString(h.y);
            case "z" -> Double.toString(h.z);
            case "dim" -> {
                if (h.dim == null) yield "";
                yield h.dim.location().toString();
            }
            case "page", "pages", "page.title" -> page(key, h, ctx);
            case "menu" -> ctx.viewer() instanceof ServerPlayer sp ? nz(HoloMenuState.get(sp, h)) : "";
            default -> "";
        };
    }

    private static String page(String key, HologramDef snap, Placeholders.PHContext ctx) {
        if (!(ctx.viewer() instanceof ServerPlayer sp) || snap.id == null) return "";
        // The context holds the viewer's filtered snapshot; page structure lives on the stored definition.
        var loc = HologramSavedData.findAnywhere(sp.getServer(), snap.id);
        HologramDef def = loc == null ? snap : loc.def();
        var accessible = def.accessiblePages(sp);
        int cur = def.coercePageFor(sp, HoloPageState.get(sp, def));
        return switch (key) {
            case "pages" -> Integer.toString(accessible.size());
            case "page.title" -> nz(def.pageTitle(cur));
            default -> Integer.toString(Math.max(0, accessible.indexOf(cur)) + 1);
        };
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
