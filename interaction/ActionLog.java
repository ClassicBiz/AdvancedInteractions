package com.luminacity.advancedinteractions.interaction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luminacity.advancedinteractions.AdvancedInteractions;
import com.luminacity.advancedinteractions.config.AIConfig;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Who clicked what: one JSON line per click in &lt;world&gt;/advancedinteractions/logs/YYYY-MM-DD.log.
 * Searchable with /aholo log; files older than the configured retention are deleted.
 */
@Mod.EventBusSubscriber(modid = AdvancedInteractions.MODID)
public final class ActionLog {
    private ActionLog() {}

    public record Entry(long time, String player, String uuid, String holo, String holoName, String element,
                        String action, String result) {}

    private static final List<String> PENDING = new ArrayList<>();
    private static MinecraftServer server;

    private static Path dir() {
        return server.getWorldPath(LevelResource.ROOT).resolve("advancedinteractions").resolve("logs");
    }

    /** Record a click (cheap: buffered, written every few seconds). */
    public static void log(ServerPlayer p, HologramDef def, String element, String action, String result) {
        if (server == null || p == null || !AIConfig.get(AIConfig.LOG_ENABLED, true)) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", System.currentTimeMillis());
        o.addProperty("p", p.getGameProfile().getName());
        o.addProperty("u", p.getUUID().toString());
        o.addProperty("h", def == null || def.id == null ? "" : def.id.toString());
        o.addProperty("n", def == null || def.alias == null ? "" : def.alias);
        o.addProperty("e", element == null ? "" : element);
        o.addProperty("a", action == null ? "" : action.length() > 300 ? action.substring(0, 300) : action);
        o.addProperty("r", result == null ? "" : result);
        synchronized (PENDING) {
            if (PENDING.size() < 50_000) PENDING.add(o.toString());
        }
    }

    private static void flush() {
        List<String> lines;
        synchronized (PENDING) {
            if (PENDING.isEmpty() || server == null) return;
            lines = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        try {
            Path d = dir();
            Files.createDirectories(d);
            Path f = d.resolve(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".log");
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (String l : lines) { w.write(l); w.newLine(); }
            }
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("Couldn't write the action log: {}", e.toString());
        }
    }

    /** Delete log files older than the retention period. */
    private static void prune() {
        int days = AIConfig.get(AIConfig.LOG_RETENTION_DAYS, 30);
        if (days <= 0 || server == null) return;
        LocalDate cutoff = LocalDate.now().minusDays(days);
        try (Stream<Path> files = Files.list(dir())) {
            files.forEach(f -> {
                String n = f.getFileName().toString();
                if (!n.endsWith(".log")) return;
                try {
                    if (LocalDate.parse(n.substring(0, n.length() - 4)).isBefore(cutoff)) Files.deleteIfExists(f);
                } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    /**
     * Newest-first matches. Filters are optional (null/blank = any): player name, hologram name or id
     * (partial match), and how many hours back to look.
     */
    public static List<Entry> search(String player, String holo, double hours, int limit) {
        flush();
        List<Entry> out = new ArrayList<>();
        if (server == null) return out;
        long since = hours > 0 ? System.currentTimeMillis() - (long) (hours * 3_600_000L) : 0;
        String pl = player == null ? "" : player.toLowerCase(Locale.ROOT);
        String hl = holo == null ? "" : holo.toLowerCase(Locale.ROOT);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir())) {
            s.filter(f -> f.getFileName().toString().endsWith(".log")).sorted((a, b) -> b.getFileName().compareTo(a.getFileName())).forEach(files::add);
        } catch (Exception e) {
            return out;
        }
        for (Path f : files) {
            String day = f.getFileName().toString().replace(".log", "");
            try {
                long dayEnd = LocalDate.parse(day).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (since > 0 && dayEnd < since) break;
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < limit; i--) {
                    try {
                        JsonObject o = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                        long t = o.get("t").getAsLong();
                        if (t < since) continue;
                        String p = o.get("p").getAsString();
                        if (!pl.isEmpty() && !p.toLowerCase(Locale.ROOT).contains(pl)) continue;
                        String h = o.get("h").getAsString(), n = o.get("n").getAsString();
                        if (!hl.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(hl) && !h.startsWith(hl)) continue;
                        out.add(new Entry(t, p, o.get("u").getAsString(), h, n, o.get("e").getAsString(),
                                o.get("a").getAsString(), o.get("r").getAsString()));
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            if (out.size() >= limit) break;
        }
        return out;
    }

    public static String time(long ms) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms));
    }

    private static int ticks;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++ticks % 100 == 0) flush();
        if (ticks % (20 * 60 * 60) == 0) prune();
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        server = e.getServer();
        prune();
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        flush();
        server = null;
    }
}
