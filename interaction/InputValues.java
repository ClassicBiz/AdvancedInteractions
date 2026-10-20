package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.config.AIConfig;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.InputWidget;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player entered in a hologram's input fields, read with {@code {input.NAME}}. Kept in memory
 * per player and hologram (cleared on logout). Every value is checked here before it is stored.
 */
public final class InputValues {
    private InputValues() {}

    /** player -> hologram -> name -> value */
    private static final Map<UUID, Map<UUID, Map<String, String>>> VALUES = new ConcurrentHashMap<>();

    public static Map<String, String> all(UUID player, UUID holo) {
        Map<UUID, Map<String, String>> m = VALUES.get(player);
        Map<String, String> v = m == null || holo == null ? null : m.get(holo);
        return v == null ? Map.of() : Map.copyOf(v);
    }

    public static String get(UUID player, UUID holo, String name) {
        Map<UUID, Map<String, String>> m = VALUES.get(player);
        Map<String, String> v = m == null || holo == null ? null : m.get(holo);
        return v == null ? null : v.get(name.toLowerCase(Locale.ROOT));
    }

    /** The player's value, or the field's starting value. */
    public static String valueOf(ServerPlayer p, HologramDef def, InputWidget w) {
        String v = p == null ? null : get(p.getUUID(), def.id, w.name());
        return v == null ? w.initialValue() : v;
    }

    public static void clearPlayer(UUID player) { VALUES.remove(player); }

    /**
     * Validate and store a value.
     * @return null when stored, otherwise the problem to show the player
     */
    public static String set(ServerPlayer p, HologramDef def, InputWidget w, String raw) {
        String value = raw == null ? "" : raw.trim();
        Placeholders.PHContext ctx = new Placeholders.PHContext(p.getLevel(), p, def);
        switch (w.type()) {
            case NUMBER, SLIDER -> {
                double d;
                try { d = Double.parseDouble(value.replace(",", "")); } catch (NumberFormatException e) { return "Enter a number."; }
                if (Double.isNaN(d) || Double.isInfinite(d)) return "Enter a number.";
                Double lo = num(Placeholders.apply(w.min(), ctx)), hi = num(Placeholders.apply(w.max(), ctx));
                if (lo != null && d < lo) d = lo;
                if (hi != null && d > hi) d = hi;
                if (lo != null && hi != null && hi < lo) return "Nothing available.";
                double step = w.step();
                if (step > 0) {
                    double base = lo == null ? 0 : lo;
                    d = base + Math.round((d - base) / step) * step;
                    if (hi != null && d > hi) d -= step;
                }
                value = d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "");
            }
            case TOGGLE -> value = Boolean.toString("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value));
            case DROPDOWN -> {
                String match = null;
                for (String o : w.optionList()) if (o.equalsIgnoreCase(value)) match = o;
                if (match == null) return "Pick one of the options.";
                value = match;
            }
            case TEXT -> {
                if (value.length() > w.maxLength()) value = value.substring(0, w.maxLength());
                value = value.replaceAll("[\\p{Cntrl}§{}]", ""); // no control chars, colour codes or placeholders
                String bad = AIConfig.blockedWord(value);
                if (bad != null) return "That text isn't allowed.";
            }
        }
        VALUES.computeIfAbsent(p.getUUID(), u -> new ConcurrentHashMap<>())
                .computeIfAbsent(def.id, h -> new ConcurrentHashMap<>())
                .put(w.name().toLowerCase(Locale.ROOT), value);
        return null;
    }

    private static Double num(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }

    /** {input.NAME}: the viewer's value on this hologram (or the field's starting value). */
    public static final class Provider implements Placeholders.PlaceholderProvider {
        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            if (ctx == null || ctx.holo() == null || key == null) return "";
            String name = key.toLowerCase(Locale.ROOT);
            String v = ctx.viewer() == null ? null : get(ctx.viewer().getUUID(), ctx.holo().id, name);
            if (v != null) return v;
            var w = findInput(ctx.holo(), name);
            if (w == null && ctx.holo().id != null) {
                // Hidden for this viewer (e.g. by a condition), so not in their copy: use the full hologram's field.
                var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                var loc = server == null ? null : com.luminacity.advancedinteractions.data.HologramSavedData.findAnywhere(server, ctx.holo().id);
                if (loc != null) w = findInput(loc.def(), name);
            }
            return w == null ? (args == null ? "" : args) : w.initialValue();
        }

        @Override
        public Map<String, String> keys() {
            return Map.of("NAME", "What the player entered in the input field NAME on this hologram");
        }
    }

    static InputWidget findInput(HologramDef def, String name) {
        java.util.List<java.util.List<com.luminacity.advancedinteractions.panel.PanelWidget>> all = new java.util.ArrayList<>();
        all.add(def.widgets);
        all.addAll(def.pages);
        all.addAll(def.menus.values());
        all.addAll(def.views.values());
        for (var l : all) {
            if (l == null) continue;
            for (var w : l) if (w instanceof InputWidget iw && iw.name().equalsIgnoreCase(name)) return iw;
        }
        return null;
    }
}
