package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.config.AIConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Performance controls: per-placeholder refresh rates (config "placeholderRates") and a running tally of
 * how much server time each hologram's placeholders cost, for /aholo perf.
 */
public final class PerfStats {
    private PerfStats() {}

    // ---------------------------------------------------------------- rate-limited values

    private record Cached(String value, long until) {}
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static volatile List<String[]> rates;
    private static volatile long ratesLoadedAt;

    /** Seconds between refreshes for a canonical token (0 = every time). */
    public static double rateFor(String canon) {
        long now = System.currentTimeMillis();
        if (rates == null || now - ratesLoadedAt > 30_000) {
            List<String[]> r = new ArrayList<>();
            try {
                for (String e : AIConfig.PLACEHOLDER_RATES.get()) {
                    int eq = e.indexOf('=');
                    if (eq > 0) r.add(new String[] { e.substring(0, eq).trim().toLowerCase(), e.substring(eq + 1).trim() });
                }
            } catch (Exception ignored) { }
            rates = r;
            ratesLoadedAt = now;
        }
        String c = canon.toLowerCase();
        double best = 0;
        int bestLen = -1;
        for (String[] r : rates) {
            String p = r[0];
            boolean match = c.equals(p) || c.startsWith(p + ".") || c.startsWith(p + ":")
                    || c.startsWith("link." + p + ".") || c.startsWith("link." + p + ":");
            if (match && p.length() > bestLen) {
                try { best = Double.parseDouble(r[1]); bestLen = p.length(); } catch (NumberFormatException ignored) { }
            }
        }
        return best;
    }

    /** The value for a token, re-resolved at most once per its configured rate (per viewer and hologram). */
    public static String resolve(String canon, UUID viewer, UUID holo, Supplier<String> resolver) {
        double secs = rateFor(canon);
        if (secs <= 0) return resolver.get();
        String key = canon + "|" + viewer + "|" + holo;
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(key);
        if (c != null && now < c.until()) return c.value();
        String v = resolver.get();
        CACHE.put(key, new Cached(v, now + (long) (secs * 1000)));
        if (CACHE.size() > 50_000) CACHE.entrySet().removeIf(e -> e.getValue().until() < now);
        return v;
    }

    // ---------------------------------------------------------------- cost per hologram

    private static final class Tally {
        long nanos, calls, tokens;
        long windowStart = System.currentTimeMillis();
        String name = "";
    }

    private static final Map<UUID, Tally> TALLY = new ConcurrentHashMap<>();

    public static void record(UUID holo, String name, long nanos, int tokens) {
        if (holo == null) return;
        Tally t = TALLY.computeIfAbsent(holo, k -> new Tally());
        synchronized (t) {
            t.nanos += nanos;
            t.calls++;
            t.tokens += tokens;
            if (name != null) t.name = name;
        }
    }

    public record Row(UUID id, String name, double msPerMinute, double avgMs, long calls, double avgTokens) {}

    /** Heaviest holograms first, since the last reset. */
    public static List<Row> top(int n) {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        TALLY.forEach((id, t) -> {
            synchronized (t) {
                double minutes = Math.max(1.0 / 60, (now - t.windowStart) / 60_000.0);
                rows.add(new Row(id, t.name, t.nanos / 1e6 / minutes, t.calls == 0 ? 0 : t.nanos / 1e6 / t.calls,
                        t.calls, t.calls == 0 ? 0 : (double) t.tokens / t.calls));
            }
        });
        rows.sort(Comparator.comparingDouble(Row::msPerMinute).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    public static void reset() { TALLY.clear(); CACHE.clear(); }
}
