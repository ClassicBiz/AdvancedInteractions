package com.luminacity.advancedinteractions.client;

import com.luminacity.advancedinteractions.api.Placeholders;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only cache for server-resolved placeholder values.
 *
 * Values are stored per-hologram so future placeholders can depend on holo meta
 * (e.g. {estate.property.price} resolved using {meta.estateId}).
 */
public final class ClientPlaceholderCache {

    /** Special hologram id for global (non-hologram-specific) values. */
    public static final String GLOBAL = "";

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> BY_HOLO = new ConcurrentHashMap<>();

    private ClientPlaceholderCache() {}

    public static String canonical(String namespace, String key, String args) {
        String base = (namespace == null ? "" : namespace) + "." + (key == null ? "" : key);
        if (args != null && !args.isEmpty()) return base + ":" + args;
        return base;
    }

    public static void put(String holoId, String canonicalKey, String value) {
        String hid = holoId == null ? GLOBAL : holoId;
        if (canonicalKey == null || canonicalKey.isEmpty()) return;
        BY_HOLO.computeIfAbsent(hid, __ -> new ConcurrentHashMap<>())
                .put(canonicalKey, value == null ? "" : value);
    }

    /** Replace/merge values from the server. */
    public static void putAll(Map<String, Map<String, String>> map) {
        if (map == null || map.isEmpty()) return;
        for (var e : map.entrySet()) {
            String hid = e.getKey() == null ? GLOBAL : e.getKey();
            Map<String, String> vals = e.getValue();
            if (vals == null) continue;
            ConcurrentHashMap<String, String> dst = BY_HOLO.computeIfAbsent(hid, __ -> new ConcurrentHashMap<>());
            dst.clear();
            for (var kv : vals.entrySet()) {
                if (kv.getKey() == null) continue;
                dst.put(kv.getKey(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
    }

    /**
     * Remote resolver hook for the placeholder engine.
     *
     * @return resolved string, or null if we do not have it cached.
     */
    public static String resolve(String namespace, String key, String args, Placeholders.PHContext ctx) {
        String canon = canonical(namespace, key, args);
        String hid = GLOBAL;
        try {
            if (ctx != null && ctx.holo() != null && ctx.holo().id != null) hid = ctx.holo().id.toString();
        } catch (Throwable ignored) {}

        // Prefer holo-specific values, then fall back to global values.
        ConcurrentHashMap<String, String> hm = BY_HOLO.get(hid);
        if (hm != null && hm.containsKey(canon)) return hm.get(canon);

        ConcurrentHashMap<String, String> gm = BY_HOLO.get(GLOBAL);
        if (gm != null && gm.containsKey(canon)) return gm.get(canon);

        return null;
    }

    // Back-compat convenience API used by a few providers.
    public static String get(String namespace, String key, String args) {
        String v = resolve(namespace, key, args, null);
        return v == null ? "" : v;
    }

    public static void clear() {
        BY_HOLO.clear();
    }
}
