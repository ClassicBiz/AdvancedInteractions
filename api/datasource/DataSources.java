package com.luminacity.advancedinteractions.api.datasource;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of block data sources. Built in: "block", "container", "furnace", "sign". Other mods add
 * their own (a shop mod might register "shop" reading its shop block), and only when they are installed.
 */
public final class DataSources {
    private DataSources() {}

    private static final Map<String, DataSource> SOURCES = new ConcurrentHashMap<>();

    /** Register (or replace) a source; ids are lower-case letters, digits and _. */
    public static void register(String id, DataSource source) {
        if (id == null || source == null) return;
        String k = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (!k.matches("[a-z0-9_]{1,32}")) return;
        SOURCES.put(k, source);
    }

    public static DataSource get(String id) {
        return id == null ? null : SOURCES.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public static Set<String> ids() { return new TreeMap<>(SOURCES).keySet(); }
}
