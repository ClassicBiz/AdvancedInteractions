package com.luminacity.advancedinteractions.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Output filters for placeholders: {@code {namespace.key|filter|filter:arg:arg}}.
 *
 * <p>Filters run after the value is resolved, on whichever side resolved it, so the server only
 * ever syncs the raw value and the client formats it. Filter arguments are separated by ':'.</p>
 *
 * <p>Pure Java (no Minecraft imports): keep this file byte-identical across version folders.</p>
 */
public final class PlaceholderFilters {
    private PlaceholderFilters() {}

    /** A filter receives the current value and its ':'-separated arguments. */
    public interface Filter extends BiFunction<String, String[], String> {}

    private static final Map<String, Filter> FILTERS = new ConcurrentHashMap<>();
    private static final Map<String, String> DESCRIPTIONS = new ConcurrentHashMap<>();

    /** Register (or replace) a filter. Other mods may add their own. */
    public static void register(String name, String description, Filter f) {
        if (name == null || name.isBlank() || f == null) return;
        String n = name.toLowerCase(Locale.ROOT).trim();
        FILTERS.put(n, f);
        DESCRIPTIONS.put(n, description == null ? "" : description);
    }

    /** name -> description, sorted by name. */
    public static Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        DESCRIPTIONS.keySet().stream().sorted().forEach(k -> out.put(k, DESCRIPTIONS.get(k)));
        return out;
    }

    /** Apply a chain like ["round:1", "default:N/A"]. Unknown filters are skipped. */
    public static String apply(String value, List<String> chain) {
        String v = value == null ? "" : value;
        if (chain == null) return v;
        for (String spec : chain) {
            if (spec == null || spec.isBlank()) continue;
            String[] parts = spec.split(":", -1);
            Filter f = FILTERS.get(parts[0].trim().toLowerCase(Locale.ROOT));
            if (f == null) continue;
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            try {
                String out = f.apply(v, args);
                v = out == null ? "" : out;
            } catch (RuntimeException ignored) {
                // A bad argument leaves the value unchanged rather than blanking the panel.
            }
        }
        return v;
    }

    // ---------------------------------------------------------------- helpers

    private static String arg(String[] a, int i, String def) {
        return a.length > i && !a[i].isEmpty() ? a[i] : def;
    }

    private static int intArg(String[] a, int i, int def) {
        try { return a.length > i && !a[i].isBlank() ? Integer.parseInt(a[i].trim()) : def; }
        catch (NumberFormatException e) { return def; }
    }

    /** Parses "1,234.5", " 12 ", "3.0" etc. Returns null when the value is not numeric. */
    static Double num(String v) {
        if (v == null) return null;
        String s = v.replace(",", "").trim();
        if (s.isEmpty()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String fmt(double d, int decimals) {
        return BigDecimal.valueOf(d).setScale(Math.max(0, decimals), RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return !(s.isEmpty() || s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off"));
    }

    private static String duration(long totalSeconds, boolean clock) {
        long s = Math.max(0, totalSeconds);
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (clock) {
            return d > 0 ? String.format(Locale.ROOT, "%dd %02d:%02d:%02d", d, h, m, sec)
                         : String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec);
        }
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        if (d > 0 || h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(sec).append('s');
        return sb.toString();
    }

    private static long toSeconds(double v, String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "ticks", "t" -> (long) (v / 20.0);
            case "ms" -> (long) (v / 1000.0);
            case "m", "min", "minutes" -> (long) (v * 60.0);
            case "h", "hours" -> (long) (v * 3600.0);
            default -> (long) v;
        };
    }

    // ---------------------------------------------------------------- built-ins

    static {
        // Text
        register("upper", "UPPERCASE", (v, a) -> v.toUpperCase(Locale.ROOT));
        register("lower", "lowercase", (v, a) -> v.toLowerCase(Locale.ROOT));
        register("title", "Title Case (also turns _ into spaces)", (v, a) -> {
            StringBuilder sb = new StringBuilder();
            boolean up = true;
            for (char c : v.replace('_', ' ').toCharArray()) {
                sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = Character.isWhitespace(c);
            }
            return sb.toString();
        });
        register("trim", "Strip surrounding spaces", (v, a) -> v.trim());
        register("strip", "Remove § colour/format codes", (v, a) -> v.replaceAll("§.", ""));
        register("max", "max:<n> - cut to n characters, adding …", (v, a) -> {
            int n = intArg(a, 0, 16);
            return v.length() <= n ? v : v.substring(0, Math.max(0, n - 1)) + "…";
        });
        register("pad", "pad:<n>[:char] - pad on the right to n characters", (v, a) -> {
            int n = intArg(a, 0, 0);
            String c = arg(a, 1, " ");
            StringBuilder sb = new StringBuilder(v);
            while (sb.length() < n) sb.append(c.charAt(0));
            return sb.toString();
        });
        register("padleft", "padleft:<n>[:char] - pad on the left (padleft:3:0 -> 007)", (v, a) -> {
            int n = intArg(a, 0, 0);
            String c = arg(a, 1, " ");
            StringBuilder sb = new StringBuilder();
            for (int i = v.length(); i < n; i++) sb.append(c.charAt(0));
            return sb.append(v).toString();
        });
        register("replace", "replace:<from>:<to>", (v, a) -> v.replace(arg(a, 0, ""), arg(a, 1, "")));
        register("prefix", "prefix:<text> - prepend only when not empty", (v, a) -> v.isEmpty() ? v : arg(a, 0, "") + v);
        register("suffix", "suffix:<text> - append only when not empty", (v, a) -> v.isEmpty() ? v : v + arg(a, 0, ""));

        // Fallbacks / logic
        register("default", "default:<text> - use text when empty", (v, a) -> v.isEmpty() ? arg(a, 0, "") : v);
        register("if", "if:<then>[:<else>] - then when the value is truthy (not empty/false/0/no)",
                (v, a) -> truthy(v) ? arg(a, 0, "") : arg(a, 1, ""));
        register("yesno", "true -> Yes, false -> No", (v, a) -> truthy(v) ? arg(a, 0, "Yes") : arg(a, 1, "No"));
        register("map", "map:a=x,b=y[,*=z] - translate values (case-insensitive, * = anything else)", (v, a) -> {
            String spec = String.join(":", a);
            String fallback = v;
            for (String pair : spec.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String k = pair.substring(0, eq).trim();
                String out = pair.substring(eq + 1);
                if (k.equals("*")) fallback = out;
                else if (k.equalsIgnoreCase(v.trim())) return out;
            }
            return fallback;
        });

        // Numbers
        register("round", "round[:decimals] - round (default 0 decimals)", (v, a) -> {
            Double d = num(v);
            return d == null ? v : fmt(d, intArg(a, 0, 0));
        });
        register("int", "Drop decimals", (v, a) -> {
            Double d = num(v);
            return d == null ? v : Long.toString((long) Math.floor(d));
        });
        register("number", "number[:decimals] - thousands separators (1,234,567)", (v, a) -> {
            Double d = num(v);
            if (d == null) return v;
            int dec = intArg(a, 0, 0);
            DecimalFormat df = new DecimalFormat(dec > 0 ? "#,##0." + "0".repeat(dec) : "#,##0",
                    DecimalFormatSymbols.getInstance(Locale.ROOT));
            return df.format(d);
        });
        register("compact", "compact[:decimals] - 1.2K, 3.4M, 5B", (v, a) -> {
            Double d = num(v);
            if (d == null) return v;
            int dec = intArg(a, 0, 1);
            double abs = Math.abs(d);
            String[] units = { "", "K", "M", "B", "T" };
            int u = 0;
            while (abs >= 1000 && u < units.length - 1) { abs /= 1000; u++; }
            String s = u == 0 ? fmt(abs, 0) : fmt(abs, dec);
            if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            return (d < 0 ? "-" : "") + s + units[u];
        });
        register("percent", "percent:<max>[:decimals] - value as % of max (health|percent:20)", (v, a) -> {
            Double d = num(v);
            Double max = num(arg(a, 0, "1"));
            if (d == null || max == null || max == 0) return v;
            return fmt(d / max * 100.0, intArg(a, 1, 0)) + "%";
        });
        register("add", "add:<n>", (v, a) -> { Double d = num(v), n = num(arg(a, 0, "0")); return d == null || n == null ? v : trimZeros(d + n); });
        register("sub", "sub:<n>", (v, a) -> { Double d = num(v), n = num(arg(a, 0, "0")); return d == null || n == null ? v : trimZeros(d - n); });
        register("mul", "mul:<n>", (v, a) -> { Double d = num(v), n = num(arg(a, 0, "1")); return d == null || n == null ? v : trimZeros(d * n); });
        register("div", "div:<n>", (v, a) -> { Double d = num(v), n = num(arg(a, 0, "1")); return d == null || n == null || n == 0 ? v : trimZeros(d / n); });
        register("bar", "bar:<max>[:width[:full[:empty]]] - progress bar (§a■■■■§7■■)", (v, a) -> {
            Double d = num(v);
            Double max = num(arg(a, 0, "100"));
            if (d == null || max == null || max <= 0) return v;
            int width = Math.max(1, Math.min(100, intArg(a, 1, 10)));
            int filled = (int) Math.round(Math.max(0, Math.min(1, d / max)) * width);
            String full = arg(a, 2, "§a■");
            String empty = arg(a, 3, "§7■");
            return full.repeat(filled) + empty.repeat(width - filled);
        });
        register("color", "color:<good>:<warn>[:low] - §a/§e/§c by threshold (add :low when lower is better)", (v, a) -> {
            Double d = num(v), good = num(arg(a, 0, "")), warn = num(arg(a, 1, ""));
            if (d == null || good == null || warn == null) return v;
            boolean lowBetter = arg(a, 2, "").equalsIgnoreCase("low");
            String c = lowBetter
                    ? (d <= good ? "§a" : d <= warn ? "§e" : "§c")
                    : (d >= good ? "§a" : d >= warn ? "§e" : "§c");
            return c + v;
        });

        // Time
        register("duration", "duration[:unit] - 3725 -> 1h 2m 5s (unit: s, ticks, ms, m, h)", (v, a) -> {
            Double d = num(v);
            return d == null ? v : duration(toSeconds(d, arg(a, 0, "s")), false);
        });
        register("clock", "clock[:unit] - 3725 -> 01:02:05", (v, a) -> {
            Double d = num(v);
            return d == null ? v : duration(toSeconds(d, arg(a, 0, "s")), true);
        });
    }

    private static String trimZeros(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
