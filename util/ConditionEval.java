package com.luminacity.advancedinteractions.util;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Evaluates widget visibility conditions ("visibleIf") server-side, per viewer.
 *
 * <p>Syntax (whitespace-tolerant):</p>
 * <ul>
 *   <li>{@code has(x)}       - placeholder x resolves to a non-empty value. x may be
 *       "price" (shorthand for data.price), "estate.property.price" or "{estate.property.price}".</li>
 *   <li>{@code eq(x,value)}  - placeholder x equals value (case-insensitive).</li>
 *   <li>{@code ne(x,value)}  - negation of eq.</li>
 *   <li>{@code gt(x,n)} {@code gte(x,n)} {@code lt(x,n)} {@code lte(x,n)} - numeric compare; n may also be
 *       a placeholder ({@code gte(econ.player.balance,{data.price})}). Non-numbers are false.</li>
 *   <li>{@code contains(x,text)} {@code starts(x,text)} - case-insensitive text checks.</li>
 *   <li>{@code empty(x)}     - negation of has.</li>
 *   <li>{@code perm(node)}   - viewer has the LuckPerms node.</li>
 *   <li>{@code op(level)}    - viewer has the vanilla op level.</li>
 *   <li>{@code true} / {@code false}</li>
 *   <li>Schedules: {@code between(18:00,23:30)} real (server) time, wraps past midnight;
 *       {@code weekday(sat,sun)}; {@code night()} / {@code day()} in-game; {@code gametime(13000,23000)}
 *       ticks of the in-game day; {@code rotate(SECONDS,INDEX,COUNT)} true for one slot of a rotation
 *       (show COUNT elements in turn, INDEX from 0); {@code flag(NAME)} a server event flag is on.</li>
 *   <li>Prefix {@code !} to negate; combine with {@code &&} and {@code ||} (|| binds loosest).</li>
 * </ul>
 * Unknown expressions evaluate to false so a typo hides the widget instead of leaking it.
 */
public final class ConditionEval {
    private ConditionEval() {}

    /** A condition function other mods or scripts add, used as {@code name(arg)} in visibleIf. */
    public interface Custom {
        boolean test(String arg, Placeholders.PHContext ctx);
    }

    private static final java.util.Map<String, Custom> CUSTOM = new java.util.concurrent.ConcurrentHashMap<>();

    /** Register {@code name(arg)}; built-in names (has, eq, perm...) can't be replaced. */
    public static void register(String name, Custom fn) {
        if (name == null || fn == null) return;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{1,32}")) return;
        CUSTOM.put(n, fn);
    }

    public static java.util.Set<String> customNames() { return CUSTOM.keySet(); }

    public static boolean test(String expr, HologramDef holo, ServerPlayer viewer, Level level) {
        if (expr == null || expr.isBlank()) return true;
        Placeholders.PHContext ctx = new Placeholders.PHContext(level, viewer, holo);
        for (String orPart : expr.split("\\|\\|")) {
            boolean all = true;
            for (String andPart : orPart.split("&&")) {
                if (!term(andPart.trim(), ctx, viewer)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean term(String t, Placeholders.PHContext ctx, ServerPlayer viewer) {
        if (t.isEmpty()) return true;
        if (t.startsWith("!")) return !term(t.substring(1).trim(), ctx, viewer);

        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("true")) return true;
        if (low.equals("false")) return false;

        int open = t.indexOf('(');
        int close = t.lastIndexOf(')');
        if (open <= 0 || close < open) return false;
        String fn = low.substring(0, open).trim();
        String arg = t.substring(open + 1, close).trim();

        try {
            switch (fn) {
                case "has": return !resolve(arg, ctx).isEmpty();
                case "empty": return resolve(arg, ctx).isEmpty();
                case "eq": case "ne": {
                    int comma = topComma(arg);
                    if (comma < 0) return false;
                    String v = resolve(arg.substring(0, comma).trim(), ctx);
                    String want = arg.substring(comma + 1).trim();
                    boolean eq = v.equalsIgnoreCase(want);
                    return fn.equals("eq") == eq;
                }
                case "contains": case "starts": {
                    int comma = topComma(arg);
                    if (comma < 0) return false;
                    String v = resolve(arg.substring(0, comma).trim(), ctx).toLowerCase(Locale.ROOT);
                    String want = arg.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
                    return fn.equals("contains") ? v.contains(want) : v.startsWith(want);
                }
                case "gt": case "gte": case "lt": case "lte": {
                    int comma = topComma(arg);
                    if (comma < 0) return false;
                    Double a = number(resolve(arg.substring(0, comma).trim(), ctx));
                    String rhs = arg.substring(comma + 1).trim();
                    Double b = number(rhs.startsWith("{") ? resolve(rhs, ctx) : rhs);
                    if (a == null || b == null) return false;
                    int c = Double.compare(a, b);
                    return switch (fn) {
                        case "gt" -> c > 0;
                        case "gte" -> c >= 0;
                        case "lt" -> c < 0;
                        default -> c <= 0;
                    };
                }
                case "between": {
                    String[] p = arg.split(",");
                    if (p.length != 2) return false;
                    int from = minutes(p[0]), to = minutes(p[1]);
                    java.time.LocalTime now = java.time.LocalTime.now();
                    int m = now.getHour() * 60 + now.getMinute();
                    return from <= to ? m >= from && m < to : m >= from || m < to;
                }
                case "weekday": {
                    String today = java.time.LocalDate.now().getDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
                    for (String d : arg.split(",")) if (d.trim().toLowerCase(Locale.ROOT).startsWith(today)) return true;
                    return false;
                }
                case "night": case "day": {
                    if (ctx.level() == null) return false;
                    long dt = ctx.level().getDayTime() % 24000L;
                    boolean night = dt >= 13000 && dt < 23000;
                    return fn.equals("night") == night;
                }
                case "gametime": {
                    String[] p = arg.split(",");
                    if (p.length != 2 || ctx.level() == null) return false;
                    long dt = ctx.level().getDayTime() % 24000L, a = Long.parseLong(p[0].trim()), b = Long.parseLong(p[1].trim());
                    return a <= b ? dt >= a && dt < b : dt >= a || dt < b;
                }
                case "rotate": {
                    String[] p = arg.split(",");
                    if (p.length != 3) return false;
                    double secs = Double.parseDouble(p[0].trim());
                    int index = Integer.parseInt(p[1].trim()), count = Integer.parseInt(p[2].trim());
                    if (secs <= 0 || count <= 0) return false;
                    long slot = (long) Math.floor(System.currentTimeMillis() / 1000.0 / secs);
                    return Math.floorMod(slot, count) == index;
                }
                case "flag": return com.luminacity.advancedinteractions.api.Flags.get(arg.trim());
                case "perm": return viewer != null && !arg.isEmpty() && PermUtil.hasPermission(viewer, arg);
                case "op": return viewer != null && viewer.hasPermissions(Math.max(0, Math.min(4, Integer.parseInt(arg))));
                default: {
                    Custom c = CUSTOM.get(fn);
                    return c != null && c.test(arg, ctx);
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** First comma outside {placeholders} and (brackets), so {trade.max:buy,x,2} stays one argument. */
    private static int topComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '(' || c == '[') depth++;
            else if ((c == '}' || c == ')' || c == ']') && depth > 0) depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    /** "18:30" -> minutes after midnight. */
    private static int minutes(String hhmm) {
        String[] p = hhmm.trim().split(":");
        int h = Integer.parseInt(p[0].trim());
        int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
        return Math.max(0, Math.min(24 * 60, h * 60 + m));
    }

    /** "1,234.5" -> 1234.5; null when not a number. */
    private static Double number(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").replace("%", "").trim();
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    private static String resolve(String token, Placeholders.PHContext ctx) {
        String raw = token;
        if (raw.startsWith("{") && raw.endsWith("}") && raw.length() >= 2) raw = raw.substring(1, raw.length() - 1);
        if (raw.indexOf('{') >= 0) return Placeholders.apply("{" + raw + "}", ctx).trim(); // nested placeholders
        String v = Placeholders.resolveToken(raw, ctx);
        return v == null ? "" : v.trim();
    }
}
