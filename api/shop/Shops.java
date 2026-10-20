package com.luminacity.advancedinteractions.api.shop;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shop handlers by id. "basic" is built in: an admin shop that sells/buys an item for a fixed price
 * through whatever economy mod is registered ({@code item=minecraft:diamond&price=10}).
 */
public final class Shops {
    private Shops() {}

    public static final String BASIC = "basic";
    private static final Map<String, ShopHandler> HANDLERS = new ConcurrentHashMap<>();

    public static void register(String id, ShopHandler handler) {
        if (id == null || handler == null) return;
        String k = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.matches("[a-z0-9_\\-]{1,48}")) HANDLERS.put(k, handler);
    }

    public static ShopHandler get(String id) {
        return HANDLERS.get(id == null || id.isBlank() ? BASIC : id.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static Set<String> ids() { return new TreeMap<>(HANDLERS).keySet(); }

    private static final Map<String, String> DESCRIPTIONS = new ConcurrentHashMap<>(
            Map.of(BASIC, "Built-in admin shop: item=ID&price=EACH, unlimited stock, paid through the economy mod"));

    /** A one-line description of a shop for the editor ("What it trades and what the trade action needs"). */
    public static void document(String id, String description) {
        if (id != null && description != null) DESCRIPTIONS.put(id.trim().toLowerCase(java.util.Locale.ROOT), description);
    }

    /** Registered shops with their descriptions, sorted. */
    public static Map<String, String> describe() {
        Map<String, String> out = new TreeMap<>();
        for (String id : HANDLERS.keySet()) out.put(id, DESCRIPTIONS.getOrDefault(id, ""));
        return out;
    }
}
