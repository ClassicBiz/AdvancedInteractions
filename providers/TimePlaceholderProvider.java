package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in {time.*}: real-world clock and countdowns, in the server's time zone unless "@Zone" is given.
 *
 * <pre>
 * {time.now}                         14:05
 * {time.now:EEEE h:mm a@Europe/London}
 * {time.until:2026-12-25}            68d 4h 3m 10s
 * {time.until.seconds:2026-12-25 18:00|clock}
 * </pre>
 */
public final class TimePlaceholderProvider implements Placeholders.PlaceholderProvider {

    private static final Map<String, DateTimeFormatter> PATTERNS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter[] DATE_TIME_INPUTS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]"),
    };

    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("now:<pattern>", "Current time, Java pattern (default HH:mm). Add @Zone, e.g. now:HH:mm@America/New_York");
        KEYS.put("date:<pattern>", "Current date (default yyyy-MM-dd)");
        KEYS.put("unix", "Unix time in seconds");
        KEYS.put("zone", "The server's time zone");
        KEYS.put("until:<when>", "Countdown to yyyy-MM-dd[ HH:mm], like 2d 4h 3m 10s; 0s once passed");
        KEYS.put("until.seconds:<when>", "Seconds left (format with |duration or |clock)");
        KEYS.put("since:<when>", "Time elapsed since a date");
        KEYS.put("since.seconds:<when>", "Seconds elapsed");
    }

    @Override public Map<String, String> keys() { return KEYS; }

    @Override public int minPeriodTicks() { return 20; }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String a = args == null ? "" : args.trim();

        // Optional "@Zone" suffix on any argument.
        ZoneId zone = ZoneId.systemDefault();
        int at = a.lastIndexOf('@');
        if (at >= 0) {
            try { zone = ZoneId.of(a.substring(at + 1).trim()); } catch (Exception ignored) {}
            a = a.substring(0, at).trim();
        }

        return switch (k) {
            case "now" -> format(ZonedDateTime.now(zone), a.isEmpty() ? "HH:mm" : a);
            case "date" -> format(ZonedDateTime.now(zone), a.isEmpty() ? "yyyy-MM-dd" : a);
            case "unix" -> Long.toString(Instant.now().getEpochSecond());
            case "zone" -> zone.getId();
            case "until" -> human(secondsBetween(Instant.now(), parse(a, zone)));
            case "until.seconds" -> str(secondsBetween(Instant.now(), parse(a, zone)));
            case "since" -> human(secondsBetween(parse(a, zone), Instant.now()));
            case "since.seconds" -> str(secondsBetween(parse(a, zone), Instant.now()));
            default -> "";
        };
    }

    private static String format(ZonedDateTime t, String pattern) {
        try {
            return t.format(PATTERNS.computeIfAbsent(pattern, p -> DateTimeFormatter.ofPattern(p, Locale.ROOT)));
        } catch (Exception e) {
            return "";
        }
    }

    private static Instant parse(String when, ZoneId zone) {
        if (when == null || when.isBlank()) return null;
        for (DateTimeFormatter f : DATE_TIME_INPUTS) {
            try { return LocalDateTime.parse(when, f).atZone(zone).toInstant(); } catch (Exception ignored) {}
        }
        try { return LocalDate.parse(when).atStartOfDay(zone).toInstant(); } catch (Exception ignored) {}
        try { return Instant.ofEpochSecond(Long.parseLong(when)); } catch (Exception ignored) {}
        return null;
    }

    /** Never negative; null when a date did not parse. */
    private static Long secondsBetween(Instant from, Instant to) {
        if (from == null || to == null) return null;
        return Math.max(0, Duration.between(from, to).getSeconds());
    }

    private static String str(Long s) { return s == null ? "" : Long.toString(s); }

    private static String human(Long s) {
        if (s == null) return "";
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h " + m + "m " + sec + "s";
        if (h > 0) return h + "h " + m + "m " + sec + "s";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
