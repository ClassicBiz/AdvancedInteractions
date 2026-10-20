package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Locale;

/** Built-in {world.*} placeholder provider. */
public final class WorldPlaceholderProvider implements Placeholders.PlaceholderProvider {

    private static final java.util.Map<String, String> KEYS = new java.util.LinkedHashMap<>();
    static {
        KEYS.put("name", "Dimension id of the hologram's world (alias: id)");
        KEYS.put("players", "Players in this dimension");
        KEYS.put("time", "In-game clock HH:mm (time.alt adds the day)");
        KEYS.put("time.day", "In-game day number");
        KEYS.put("weather", "clear / rain / thunder");
        KEYS.put("mobs:<category>", "Mob count, optional category (monster, creature...)");
        KEYS.put("mobs.max:<category>", "Mob cap");
    }

    @Override public java.util.Map<String, String> keys() { return KEYS; }

    @Override
    public String resolve(String key, String argsRaw, Placeholders.PHContext ctx) {
        if (ctx == null || ctx.level() == null) return "";
        Level level = ctx.level();
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String args = argsRaw == null ? "" : argsRaw.trim();

        return switch (k) {
            case "id" -> {
                ResourceLocation rl = level.dimension().location();
                yield rl == null ? "" : rl.toString();
            }
            case "name" -> {
                ResourceLocation rl = level.dimension().location();
                yield rl == null ? "" : rl.toString();
            }
            case "players" -> Integer.toString(level.players().size());

            case "time" -> formatMcTime(level.getDayTime(), false);
            case "time.alt" -> formatMcTime(level.getDayTime(), true);
            case "time.day" -> Long.toString(level.getDayTime() / 24000L);

            case "weather" -> weather(level);

            // Mob counts/caps from the last natural-spawn pass (cheap). Optional category arg:
            //   {world.mobs}  {world.mobs:monster}  {world.mobs.max:creature}
            case "mobs" -> mobCount(level, args);
            case "mobs.max" -> mobCap(level, args);

            default -> "";
        };
    }

    private static net.minecraft.world.entity.MobCategory category(String arg) {
        if (arg == null || arg.isBlank()) return null;
        String a = arg.trim().toLowerCase(Locale.ROOT);
        for (var c : net.minecraft.world.entity.MobCategory.values()) {
            if (c.getName().equalsIgnoreCase(a) || c.name().equalsIgnoreCase(a)) return c;
        }
        return null;
    }

    private static String mobCount(Level level, String arg) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return "";
        var cat = category(arg);
        if (arg != null && !arg.isBlank() && cat == null) return "";
        var state = sl.getChunkSource().getLastSpawnState();
        if (state != null) {
            var counts = state.getMobCategoryCounts();
            if (cat != null) return Integer.toString(counts.getInt(cat));
            int total = 0;
            for (var c : net.minecraft.world.entity.MobCategory.values()) total += counts.getInt(c);
            return Integer.toString(total);
        }
        // Before the first spawn pass: count loaded mobs directly.
        int n = 0;
        for (var e : sl.getAllEntities()) {
            if (!(e instanceof net.minecraft.world.entity.Mob m)) continue;
            if (cat == null || m.getType().getCategory() == cat) n++;
        }
        return Integer.toString(n);
    }

    private static String mobCap(Level level, String arg) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return "";
        var cat = category(arg);
        if (arg != null && !arg.isBlank() && cat == null) return "";
        var state = sl.getChunkSource().getLastSpawnState();
        if (state == null) return "";
        int chunks = state.getSpawnableChunkCount();
        final int magic = 17 * 17; // NaturalSpawner.MAGIC_NUMBER
        int total = 0;
        for (var c : net.minecraft.world.entity.MobCategory.values()) {
            if (cat != null && c != cat) continue;
            int per = c.getMaxInstancesPerChunk();
            if (per <= 0) continue;
            total += per * chunks / magic;
        }
        return Integer.toString(total);
    }

    private static String weather(Level level) {
        if (level.isThundering()) return "thunder";
        if (level.isRaining()) return "rain";
        return "clear";
    }

    private static String formatMcTime(long dayTime, boolean alt) {
        // Vanilla dayTime is ticks since world start. Time-of-day is dayTime % 24000.
        long t = ((dayTime % 24000L) + 24000L) % 24000L;

        // Minecraft 0 ticks is 6:00 AM. Convert ticks to minutes.
        long minutes = (t * 60L) / 1000L;
        long hours = (minutes / 60L + 6L) % 24L;
        long mins = minutes % 60L;

        if (!alt) {
            return String.format(Locale.ROOT, "%02d:%02d", hours, mins);
        }

        long day = dayTime / 24000L;
        return "Day " + day + " " + String.format(Locale.ROOT, "%02d:%02d", hours, mins);
    }
}
