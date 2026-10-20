package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Built-in {server.*} placeholder provider.
 *
 * Note: some values (tps/mspt/ram/modlist) are only available on the logical server.
 * When resolved client-side, these return "".
 */
public final class ServerPlaceholderProvider implements Placeholders.PlaceholderProvider {

    private static final long START_MS = System.currentTimeMillis();

    private static final java.util.Map<String, String> KEYS = new java.util.LinkedHashMap<>();
    static {
        KEYS.put("name", "Server MOTD (alias: title)");
        KEYS.put("players", "Online players (players.max for the limit)");
        KEYS.put("uptime", "Time since start");
        KEYS.put("time:<pattern>", "Real time, Java pattern (default HH:mm:ss; time.alt = h:mm a)");
        KEYS.put("tps", "Ticks per second (tps.color: green/yellow/red)");
        KEYS.put("mspt", "Milliseconds per tick (mspt.color)");
        KEYS.put("ram.used:<gb|mb>", "Used memory (ram.max for the limit)");
        KEYS.put("version", "Minecraft version (brand for the server software)");
        KEYS.put("mod.count", "Number of loaded mods");
        KEYS.put("mod:<modid>", "A mod's version (mod.name:<modid> for its name)");
    }

    @Override public java.util.Map<String, String> keys() { return KEYS; }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null || ctx.level() == null) return "";
        MinecraftServer srv = ctx.level().getServer();
        if (srv == null) return "";

        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String a = args == null ? "" : args.trim();

        return switch (k) {
            case "online" -> "true";
            case "name" -> safe(srv.getMotd());
            case "title" -> safe(srv.getMotd());
            case "version" -> safe(srv.getServerVersion());
            case "brand" -> safe(srv.getServerModName());
            case "players" -> Integer.toString(srv.getPlayerList().getPlayerCount());
            case "players.max" -> Integer.toString(srv.getPlayerList().getMaxPlayers());

            case "uptime" -> formatUptime(System.currentTimeMillis() - START_MS);

            case "time" -> formatTime(a.isEmpty() ? "HH:mm:ss" : a);
            case "time.alt" -> formatTime(a.isEmpty() ? "h:mm a" : a);

            case "mspt" -> formatMspt(srv.getAverageTickTime());
            case "mspt.color" -> colorForMspt(srv.getAverageTickTime());
            case "tps" -> formatTps(tpsForMspt(srv.getAverageTickTime()));
            case "tps.color" -> colorForTps(tpsForMspt(srv.getAverageTickTime()));

            case "ram.used" -> formatBytes(usedBytes(), a);
            case "ram.max" -> formatBytes(Runtime.getRuntime().maxMemory(), a);

            case "mod.count" -> Integer.toString(ModList.get().getMods().size());
            case "mod" -> modVersion(a);
            case "mod.name" -> modName(a);

            default -> "";
        };
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String formatTime(String pattern) {
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
            return ZonedDateTime.now().format(fmt);
        } catch (Throwable t) {
            // Bad pattern -> fall back
            try {
                return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static String formatUptime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000L;
        long days = s / 86400L;
        s %= 86400L;
        long hours = s / 3600L;
        s %= 3600L;
        long mins = s / 60L;
        long secs = s % 60L;

        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }

    private static double tpsForMspt(float mspt) {
        if (mspt <= 0f) return 20.0;
        double tps = 1000.0 / mspt;
        return Math.min(20.0, Math.max(0.0, tps));
    }

    private static String formatMspt(float mspt) {
        return String.format(Locale.ROOT, "%.2f", (double) mspt);
    }

    private static String formatTps(double tps) {
        return String.format(Locale.ROOT, "%.2f", tps);
    }

    private static String colorForMspt(float mspt) {
        if (mspt <= 50.0f) return "green";
        if (mspt <= 70.0f) return "yellow";
        return "red";
    }

    private static String colorForTps(double tps) {
        if (tps >= 19.0) return "green";
        if (tps >= 15.0) return "yellow";
        return "red";
    }

    private static long usedBytes() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return Math.max(0L, used);
    }

    private static String formatBytes(long bytes, String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if ("gb".equals(u) || "g".equals(u)) {
            return String.format(Locale.ROOT, "%.2f", bytes / 1073741824.0);
        }
        // default mb
        return String.format(Locale.ROOT, "%.2f", bytes / 1048576.0);
    }

    private static String modVersion(String id) {
        if (id == null || id.isBlank()) return "";
        return ModList.get().getModContainerById(id)
                .map(c -> safe(c.getModInfo().getVersion().toString()))
                .orElse("");
    }

    private static String modName(String id) {
        if (id == null || id.isBlank()) return "";
        return ModList.get().getModContainerById(id)
                .map(c -> safe(c.getModInfo().getDisplayName()))
                .orElse("");
    }
}
