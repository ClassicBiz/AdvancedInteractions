package com.luminacity.advancedinteractions.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player variables that buttons set and placeholders/conditions read ({state.key}), so a toggle
 * stays on for that player across relogs. Stored with the world (overworld data folder).
 *
 * <p>Other mods may read and write these too: {@code PlayerState.get(server).set(uuid, "key", "value")}.</p>
 */
public final class PlayerState extends SavedData {
    private static final String NAME = "advancedinteractions_player_state";
    public static final int MAX_KEY = 64;
    public static final int MAX_VALUE = 256;
    public static final int MAX_KEYS_PER_PLAYER = 256;

    private final Map<UUID, Map<String, String>> values = new HashMap<>();

    public static PlayerState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlayerState::load, PlayerState::new, NAME);
    }

    public String get(UUID player, String key) {
        Map<String, String> m = values.get(player);
        return m == null ? null : m.get(normalize(key));
    }

    /** @return false when the key/value is too long or the player already has too many keys */
    public boolean set(UUID player, String key, String value) {
        String k = normalize(key);
        if (k.isEmpty() || k.length() > MAX_KEY) return false;
        if (value == null) { remove(player, k); return true; }
        String v = value.length() > MAX_VALUE ? value.substring(0, MAX_VALUE) : value;
        Map<String, String> m = values.computeIfAbsent(player, u -> new LinkedHashMap<>());
        if (!m.containsKey(k) && m.size() >= MAX_KEYS_PER_PLAYER) return false;
        if (!v.equals(m.put(k, v))) setDirty();
        return true;
    }

    public void remove(UUID player, String key) {
        Map<String, String> m = values.get(player);
        if (m != null && m.remove(normalize(key)) != null) {
            if (m.isEmpty()) values.remove(player);
            setDirty();
        }
    }

    /** Every player that has this key, with their value (for leaderboards). */
    public Map<UUID, String> everyone(String key) {
        String k = normalize(key);
        Map<UUID, String> out = new HashMap<>();
        values.forEach((uuid, m) -> { String v = m.get(k); if (v != null) out.put(uuid, v); });
        return out;
    }

    public Map<String, String> all(UUID player) {
        Map<String, String> m = values.get(player);
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag players = new CompoundTag();
        values.forEach((uuid, m) -> {
            CompoundTag p = new CompoundTag();
            m.forEach(p::putString);
            players.put(uuid.toString(), p);
        });
        tag.put("players", players);
        return tag;
    }

    private static PlayerState load(CompoundTag tag) {
        PlayerState s = new PlayerState();
        CompoundTag players = tag.getCompound("players");
        for (String id : players.getAllKeys()) {
            UUID uuid;
            try { uuid = UUID.fromString(id); } catch (IllegalArgumentException e) { continue; }
            CompoundTag p = players.getCompound(id);
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : p.getAllKeys()) m.put(k, p.getString(k));
            if (!m.isEmpty()) s.values.put(uuid, m);
        }
        return s;
    }
}
