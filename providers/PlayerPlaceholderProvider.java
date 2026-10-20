package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.util.PermUtil;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Built-in {player.*} placeholder provider (always the viewing player). */
public final class PlayerPlaceholderProvider implements Placeholders.PlaceholderProvider {

    /** Keys the client resolves from its own player every frame; everything else comes from the server. */
    private static final Set<String> CLIENT_KEYS = Set.of(
            "name", "displayname", "uuid", "x", "y", "z", "blockx", "blocky", "blockz", "yaw", "pitch",
            "health", "health.current", "health.max", "hunger", "food", "saturation", "inventory", "equipment",
            "level", "xp", "xp.progress", "dimension", "biome", "armor", "air", "flying", "sneaking", "held");

    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("name", "Player name");
        KEYS.put("displayname", "Display name (team prefix, nickname mods)");
        KEYS.put("uuid", "Player UUID");
        KEYS.put("x", "Exact X (also y, z)");
        KEYS.put("blockx", "Block X (also blocky, blockz)");
        KEYS.put("yaw", "Look yaw (also pitch)");
        KEYS.put("dimension", "Dimension id, e.g. minecraft:overworld");
        KEYS.put("biome", "Biome id at the player's feet");
        KEYS.put("health", "Health, 1 decimal (health.max for max)");
        KEYS.put("hunger", "Food level 0-20 (alias: food)");
        KEYS.put("saturation", "Saturation");
        KEYS.put("thirst", "Tough As Nails thirst, else the 'thirst' objective");
        KEYS.put("armor", "Armor points");
        KEYS.put("air", "Air supply in ticks (300 = full)");
        KEYS.put("level", "XP level");
        KEYS.put("xp", "Total XP points");
        KEYS.put("xp.progress", "Progress to next level, 0-100");
        KEYS.put("gamemode", "survival / creative / adventure / spectator");
        KEYS.put("flying", "true / false");
        KEYS.put("sneaking", "true / false");
        KEYS.put("op", "Op level 0-4");
        KEYS.put("perm:<node>", "true if the player has the permission (LuckPerms, else op 2)");
        KEYS.put("team", "Scoreboard team name");
        KEYS.put("held", "Name of the item in the main hand");
        KEYS.put("inventory:<slot>", "Item id in inventory slot 0-35");
        KEYS.put("equipment:<slot>", "Item id in mainhand/offhand/head/chest/legs/feet");
        KEYS.put("item.count:<item>", "How many of an item the player carries");
        KEYS.put("effect:<effect>", "Effect level (e.g. effect:speed -> 2), empty if none");
        KEYS.put("effect.time:<effect>", "Effect seconds left");
        KEYS.put("objective:<name>", "Score in a scoreboard objective");
        KEYS.put("kills", "Player kills (also mobkills, deaths)");
        KEYS.put("stat:<id>", "Any vanilla custom stat, e.g. stat:jump, stat:walk_one_cm");
        KEYS.put("ping", "Latency in ms (ping.color: green/yellow/red)");
        KEYS.put("playtime", "Play time like 5h 12m (playtime.seconds for raw)");
    }

    @Override public boolean isClientSide(String key) { return CLIENT_KEYS.contains(key); }

    @Override public Map<String, String> keys() { return KEYS; }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null) return "";
        Player p = ctx.viewer();
        if (p == null) return "";

        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String a = args == null ? "" : args.trim();

        return switch (k) {
            case "name" -> p.getGameProfile().getName();
            case "displayname" -> p.getDisplayName().getString();
            case "uuid" -> {
                UUID id = p.getUUID();
                yield id == null ? "" : id.toString();
            }

            // Position
            case "x" -> Double.toString(p.getX());
            case "y" -> Double.toString(p.getY());
            case "z" -> Double.toString(p.getZ());
            case "blockx" -> Integer.toString(p.blockPosition().getX());
            case "blocky" -> Integer.toString(p.blockPosition().getY());
            case "blockz" -> Integer.toString(p.blockPosition().getZ());
            case "yaw" -> Float.toString(p.getYRot());
            case "pitch" -> Float.toString(p.getXRot());

            // Health
            case "health", "health.current" -> String.format(Locale.ROOT, "%.1f", (double) p.getHealth());
            case "health.max" -> String.format(Locale.ROOT, "%.1f", (double) p.getMaxHealth());

            // Food
            case "hunger", "food" -> Integer.toString(p.getFoodData().getFoodLevel());
            case "saturation" -> String.format(Locale.ROOT, "%.1f", (double) p.getFoodData().getSaturationLevel());
            case "thrist", "thirst" -> thirst(ctx, p); // Tough As Nails, else a "thirst" scoreboard objective

            // Inventory / equipment
            case "inventory" -> itemId(getInventorySlot(p, a));
            case "equipment" -> itemId(getEquipmentSlot(p, a));

            // Scoreboard objective
            case "objective" -> objectiveScore(ctx, p, a);

            // Ping
            case "ping" -> ping(p);
            case "ping.color" -> pingColor(p);

            // Playtime
            case "playtime" -> playtime(p);
            case "playtime.seconds" -> stat(p, Stats.PLAY_TIME, 20);

            // World / state
            case "dimension" -> p.getLevel().dimension().location().toString();
            case "biome" -> p.getLevel().getBiome(p.blockPosition()).unwrapKey()
                    .map(biome -> biome.location().toString()).orElse("");
            case "armor" -> Integer.toString(p.getArmorValue());
            case "air" -> Integer.toString(p.getAirSupply());
            case "flying" -> Boolean.toString(p.getAbilities().flying);
            case "sneaking" -> Boolean.toString(p.isShiftKeyDown());
            case "held" -> p.getMainHandItem().isEmpty() ? "" : p.getMainHandItem().getHoverName().getString();
            case "team" -> p.getTeam() == null ? "" : p.getTeam().getName();

            // Experience
            case "level" -> Integer.toString(p.experienceLevel);
            case "xp" -> Integer.toString(p.totalExperience);
            case "xp.progress" -> Integer.toString(Math.round(p.experienceProgress * 100f));

            // Server-only state
            case "gamemode" -> p instanceof ServerPlayer sp ? sp.gameMode.getGameModeForPlayer().getName() : "";
            case "op" -> p instanceof ServerPlayer sp ? Integer.toString(sp.server.getProfilePermissions(sp.getGameProfile())) : "";
            case "perm" -> p instanceof ServerPlayer sp && !a.isEmpty() ? Boolean.toString(PermUtil.check(sp, a, -1)) : "";
            case "effect" -> effect(p, a, false);
            case "effect.time" -> effect(p, a, true);
            case "item.count" -> itemCount(p, a);

            // Statistics
            case "kills" -> stat(p, Stats.PLAYER_KILLS, 1);
            case "mobkills" -> stat(p, Stats.MOB_KILLS, 1);
            case "deaths" -> stat(p, Stats.DEATHS, 1);
            case "stat" -> customStat(p, a);

            default -> "";
        };
    }

    private static ResourceLocation id(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return ResourceLocation.tryParse(s.indexOf(':') < 0 ? "minecraft:" + s : s);
    }

    private static String effect(Player p, String effectId, boolean seconds) {
        if (!(p instanceof ServerPlayer)) return "";
        ResourceLocation rl = id(effectId);
        MobEffect eff = rl == null ? null : ForgeRegistries.MOB_EFFECTS.getValue(rl);
        if (eff == null) return "";
        MobEffectInstance inst = p.getEffect(eff);
        if (inst == null) return "";
        return seconds ? Integer.toString(inst.getDuration() / 20) : Integer.toString(inst.getAmplifier() + 1);
    }

    private static String itemCount(Player p, String itemId) {
        if (!(p instanceof ServerPlayer)) return "";
        ResourceLocation rl = id(itemId);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) return "";
        return Integer.toString(p.getInventory().countItem(ForgeRegistries.ITEMS.getValue(rl)));
    }

    /** A vanilla custom stat, divided by {@code per} (20 turns ticks into seconds). */
    private static String stat(Player p, ResourceLocation statId, int per) {
        if (!(p instanceof ServerPlayer sp)) return "";
        try {
            return Integer.toString(sp.getStats().getValue(Stats.CUSTOM.get(statId)) / Math.max(1, per));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String customStat(Player p, String raw) {
        ResourceLocation rl = id(raw);
        if (rl == null || !Registry.CUSTOM_STAT.containsKey(rl)) return "";
        return stat(p, Registry.CUSTOM_STAT.get(rl), 1);
    }

    private static ItemStack getInventorySlot(Player p, String slotArg) {
        if (p == null) return ItemStack.EMPTY;
        int slot;
        try { slot = Integer.parseInt(slotArg.trim()); } catch (Throwable t) { return ItemStack.EMPTY; }
        if (slot < 0 || slot >= p.getInventory().items.size()) return ItemStack.EMPTY;
        ItemStack st = p.getInventory().getItem(slot);
        return st == null ? ItemStack.EMPTY : st;
    }

    private static ItemStack getEquipmentSlot(Player p, String name) {
        if (p == null || name == null) return ItemStack.EMPTY;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return ItemStack.EMPTY;

        return switch (n) {
            case "mainhand" -> p.getMainHandItem();
            case "offhand" -> p.getOffhandItem();
            case "head" -> p.getItemBySlot(EquipmentSlot.HEAD);
            case "chest" -> p.getItemBySlot(EquipmentSlot.CHEST);
            case "legs" -> p.getItemBySlot(EquipmentSlot.LEGS);
            case "feet" -> p.getItemBySlot(EquipmentSlot.FEET);
            default -> ItemStack.EMPTY;
        };
    }

    private static String itemId(ItemStack st) {
        if (st == null || st.isEmpty()) return "";
        try {
            var rl = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (rl == null) return "";
            // Keep it simple: id only (count can be added later as a separate placeholder)
            return rl.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String objectiveScore(Placeholders.PHContext ctx, Player p, String objectiveName) {
        if (ctx == null || ctx.level() == null || p == null) return "";
        if (objectiveName == null || objectiveName.isBlank()) return "";
        try {
            Scoreboard sb = ctx.level().getScoreboard();
            Objective obj = sb.getObjective(objectiveName);
            if (obj == null) return "";
            String entry = p.getScoreboardName();
            return Integer.toString(sb.getOrCreatePlayerScore(entry, obj).getScore());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String ping(Player p) {
        if (!(p instanceof ServerPlayer sp)) return "";
        int v = safePing(sp);
        return v < 0 ? "" : Integer.toString(v);
    }

    private static String pingColor(Player p) {
        if (!(p instanceof ServerPlayer sp)) return "";
        int v = safePing(sp);
        if (v < 0) return "";
        if (v <= 80) return "green";
        if (v <= 200) return "yellow";
        return "red";
    }

    private static int safePing(ServerPlayer sp) {
        try {
            // Modern mappings
            try {
                Object conn = sp.connection;

                    // Try method names used in different mappings/versions
                    try {
                        var m = conn.getClass().getMethod("getLatency");
                        return (int) m.invoke(conn);
                    } catch (NoSuchMethodException ignored) {}

                    try {
                        var m = conn.getClass().getMethod("latency");
                        return (int) m.invoke(conn);
                    } catch (NoSuchMethodException ignored) {}

                    // Try field name used in many versions
                    try {
                        var f = conn.getClass().getDeclaredField("latency");
                        f.setAccessible(true);
                        Object v = f.get(conn);
                        if (v instanceof Integer i) return i;
                    } catch (Throwable ignored) {}

                    return -1;
            } catch (Throwable ignore) {}

            // Fallback: try a public field on ServerPlayer (rare)
            try {
                var f = sp.getClass().getDeclaredField("latency");
                f.setAccessible(true);
                Object v = f.get(sp);
                if (v instanceof Integer i) return i;
            } catch (Throwable ignore) {}

            // Fallback: try field on connection
            try {
                var f = sp.connection.getClass().getDeclaredField("latency");
                f.setAccessible(true);
                Object v = f.get(sp.connection);
                if (v instanceof Integer i) return i;
            } catch (Throwable ignore) {}

        } catch (Throwable ignore) {}
        return -1;
    }

    /**
     * Thirst level from a thirst mod (resolved server-side).
     * Supports Tough As Nails via reflection; otherwise falls back to a scoreboard objective named "thirst".
     */
    private static String thirst(Placeholders.PHContext ctx, Player p) {
        try {
            Class<?> helper = Class.forName("toughasnails.api.thirst.ThirstHelper");
            Object data = helper.getMethod("getThirst", Player.class).invoke(null, p);
            if (data != null) {
                Object v = data.getClass().getMethod("getThirst").invoke(data);
                if (v instanceof Integer i) return Integer.toString(i);
            }
        } catch (Throwable ignored) {}
        return objectiveScore(ctx, p, "thirst");
    }

    private static String playtime(Player p) {
        if (!(p instanceof ServerPlayer sp)) return "";
        try {
            int ticks = sp.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            long seconds = ticks / 20L;
            long hours = seconds / 3600L;
            long mins = (seconds % 3600L) / 60L;
            if (hours > 0) return hours + "h " + mins + "m";
            return mins + "m";
        } catch (Throwable t) {
            return "";
        }
    }
}
