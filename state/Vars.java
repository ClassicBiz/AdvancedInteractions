package com.luminacity.advancedinteractions.state;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Variables read with {@code {var.NAME}}: per player or global, saved with the world unless set as
 * temporary (temporary ones last until the server restarts).
 *
 * <p>{@code {var.NAME}} looks up the viewer's own value first, then the global one.
 * {@code {var.global.NAME}} and {@code {var.player.NAME}} pick one explicitly.</p>
 *
 * <p>Player values are stored in {@link PlayerState} under "var.NAME" (so {@code {state.var.NAME}} sees them too).</p>
 */
public final class Vars extends SavedData {
    private static final String NAME = "advancedinteractions_vars";
    public static final int MAX_GLOBAL = 2048;

    private final Map<String, String> global = new ConcurrentHashMap<>();
    private static final Map<String, String> TEMP_GLOBAL = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, String>> TEMP_PLAYER = new ConcurrentHashMap<>();

    public static Vars get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Vars::load, Vars::new, NAME);
    }

    public static String norm(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- reads

    public static String player(MinecraftServer server, UUID player, String key) {
        String k = norm(key);
        Map<String, String> t = TEMP_PLAYER.get(player);
        if (t != null && t.containsKey(k)) return t.get(k);
        return server == null ? null : PlayerState.get(server).get(player, "var." + k);
    }

    public static String global(MinecraftServer server, String key) {
        String k = norm(key);
        if (TEMP_GLOBAL.containsKey(k)) return TEMP_GLOBAL.get(k);
        return server == null ? null : get(server).global.get(k);
    }

    /** Player value, falling back to the global one. */
    public static String lookup(MinecraftServer server, UUID player, String key) {
        String v = player == null ? null : player(server, player, key);
        return v != null ? v : global(server, key);
    }

    // ---------------------------------------------------------------- writes

    /** @param player null = global; value null = remove */
    public static void set(MinecraftServer server, UUID player, String key, String value, boolean temporary) {
        String k = norm(key);
        if (k.isEmpty() || k.length() > PlayerState.MAX_KEY) return;
        if (value != null && value.length() > PlayerState.MAX_VALUE) value = value.substring(0, PlayerState.MAX_VALUE);
        if (player != null) {
            if (temporary) {
                Map<String, String> t = TEMP_PLAYER.computeIfAbsent(player, u -> new ConcurrentHashMap<>());
                if (value == null) t.remove(k); else t.put(k, value);
            } else {
                Map<String, String> t = TEMP_PLAYER.get(player);
                if (t != null) t.remove(k);
                if (value == null) PlayerState.get(server).remove(player, "var." + k);
                else PlayerState.get(server).set(player, "var." + k, value);
            }
            return;
        }
        if (temporary) {
            if (value == null) TEMP_GLOBAL.remove(k); else TEMP_GLOBAL.put(k, value);
            return;
        }
        TEMP_GLOBAL.remove(k);
        Vars v = get(server);
        if (value == null) { if (v.global.remove(k) != null) v.setDirty(); return; }
        if (!v.global.containsKey(k) && v.global.size() >= MAX_GLOBAL) return;
        if (!value.equals(v.global.put(k, value))) v.setDirty();
    }

    public static void clearTemp(UUID player) { TEMP_PLAYER.remove(player); }

    public static Map<String, String> globals(MinecraftServer server) {
        Map<String, String> out = new java.util.TreeMap<>(get(server).global);
        out.putAll(TEMP_GLOBAL);
        return out;
    }

    // ---------------------------------------------------------------- placeholders

    /** {var.NAME}, {var.global.NAME}, {var.player.NAME}. */
    public static final class Provider implements Placeholders.PlaceholderProvider {
        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || key == null) return "";
            UUID viewer = ctx == null || ctx.viewer() == null ? null : ctx.viewer().getUUID();
            String k = norm(key);
            String v;
            if (k.startsWith("global.")) v = global(server, k.substring(7));
            else if (k.startsWith("player.")) v = viewer == null ? null : player(server, viewer, k.substring(7));
            else v = lookup(server, viewer, k);
            if (v == null || v.isEmpty()) return args == null ? "" : args; // {var.x:default}
            return v;
        }

        @Override
        public Map<String, String> keys() {
            return Map.of("NAME", "Variable: the player's own value, else the global one ({var.coins:0} gives 0 when unset)",
                    "global.NAME", "Global variable", "player.NAME", "The player's own variable");
        }
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag g = new CompoundTag();
        global.forEach(g::putString);
        tag.put("global", g);
        return tag;
    }

    public static Vars load(CompoundTag tag) {
        Vars v = new Vars();
        CompoundTag g = tag.getCompound("global");
        for (String k : g.getAllKeys()) v.global.put(k, g.getString(k));
        return v;
    }

    /** Current server, for code without one in hand. */
    public static MinecraftServer server() { return ServerLifecycleHooks.getCurrentServer(); }
}
