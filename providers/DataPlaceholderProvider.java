package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.DataProviders;
import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * Built-in {data.*} provider (also reached through the bare {key} shorthand used by templates).
 *
 * <p>Lookup order for {data.price}:</p>
 * <ol>
 *   <li>Server-side: the hologram's data binding via {@link DataProviders} (args override the binding).</li>
 *   <li>Hologram meta "price", then meta "data.price" (works client-side too).</li>
 * </ol>
 */
public final class DataPlaceholderProvider implements Placeholders.PlaceholderProvider {

    @Override public java.util.Map<String, String> keys() {
        return java.util.Map.of("<key>", "Template variable from the data binding or meta; {key} is shorthand");
    }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null || key == null || key.isBlank()) return "";
        HologramDef holo = ctx.holo();
        if (holo == null) return "";

        String binding = (args != null && !args.isBlank()) ? args.trim() : holo.binding;
        if (binding != null && !binding.isBlank() && ctx.level() instanceof ServerLevel sl) {
            try {
                Object v = DataProviders.fetch(binding, sl, holo).get(key);
                if (v != null) {
                    String s = String.valueOf(v);
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
        }

        if (holo.meta != null) {
            String m = holo.meta.get(key);
            if (m == null) m = holo.meta.get(key.toLowerCase(Locale.ROOT));
            if (m == null) m = holo.meta.get("data." + key);
            if (m != null) return m;
        }
        return "";
    }

    /**
     * Fallback data provider: a binding of the form "namespace:args" exposes that placeholder
     * namespace as data. Example: binding "estate:testprop" makes {data.property.price}
     * resolve {estate.property.price:testprop}.
     */
    public static final class NamespaceBridge implements DataProviders.DataProvider {
        @Override
        public boolean canHandle(String binding) {
            if (binding == null) return false;
            int colon = binding.indexOf(':');
            if (colon <= 0) return false;
            String ns = binding.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            return !ns.equals("data") && Placeholders.get(ns).isPresent();
        }

        @Override
        public DataProviders.DataContext fetch(String binding, ServerLevel level, HologramDef holo) {
            int colon = binding.indexOf(':');
            String ns = binding.substring(0, colon).trim();
            String arg = binding.substring(colon + 1).trim();
            var provider = Placeholders.get(ns).orElse(null);
            if (provider == null) return key -> null;
            return new DataProviders.DataContext() {
                @Override public Object get(String key) {
                    try {
                        // Viewer is irrelevant for bound data; PHContext keeps level + holo for providers that need them.
                        String v = provider.resolve(key, arg, new Placeholders.PHContext(level, null, holo));
                        return (v == null || v.isEmpty()) ? null : v;
                    } catch (Throwable t) {
                        return null;
                    }
                }
                @Override public int minPeriodTicks() { return Math.max(1, provider.minPeriodTicks()); }
            };
        }
    }
}
