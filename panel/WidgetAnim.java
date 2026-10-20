package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A widget's animation. Evaluated while drawing (client clock), so it costs no network traffic.
 *
 * <ul>
 *   <li>pulse, blink, fade_in, slide_in, bob, spin: any widget</li>
 *   <li>scroll (marquee), typewriter, cycle (frames split by "||"), rainbow: text and button labels</li>
 * </ul>
 * {@code speed} is cycles (or characters, for typewriter/scroll) per second; {@code amount} is pixels
 * for bob/slide_in and the visible width in characters for scroll; {@code delay} waits after the widget
 * appears (fade_in, slide_in, typewriter).
 */
public final class WidgetAnim {
    public enum Type {
        NONE, PULSE, BLINK, FADE_IN, SLIDE_IN, BOB, SPIN, SCROLL, TYPEWRITER, CYCLE, RAINBOW,
        SHAKE, SWING, WOBBLE, FLOAT, BREATHE, HEARTBEAT, POP_IN, ZOOM_IN, FLICKER, GLOW
    }

    public Type type = Type.NONE;
    public float speed = 1f;
    public float amount = 8f;
    public float delay = 0f;
    /** Slide-in direction: where it comes from (left, right, up, down). */
    public String direction = "left";
    /** Play only while the player's crosshair is on the element. */
    public boolean onHover = false;

    /** Per-frame result: opacity, offset (panel px), extra rotation, scale and brightness multipliers. */
    public record Frame(float alpha, float dx, float dy, float rotation, float scale, float bright) {
        public static final Frame IDENTITY = new Frame(1f, 0f, 0f, 0f, 1f, 1f);

        public Frame(float alpha, float dx, float dy, float rotation) { this(alpha, dx, dy, rotation, 1f, 1f); }

        /** Combine with another effect (e.g. hover highlight on top of an animation). */
        public Frame times(float extraScale, float extraBright) {
            return new Frame(alpha, dx, dy, rotation, scale * extraScale, bright * extraBright);
        }
    }

    /** Effects that only make sense for text (and button labels). */
    public static boolean textOnly(Type t) {
        return t == Type.SCROLL || t == Type.TYPEWRITER || t == Type.CYCLE || t == Type.RAINBOW;
    }

    public boolean active() { return type != Type.NONE; }

    public static Type parse(String s) {
        try { return Type.valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return Type.NONE; }
    }

    // ---------------------------------------------------------------- evaluation

    /** "Appeared" times: a widget counts as newly shown if it wasn't drawn in the last half second. */
    private static final Map<String, long[]> SEEN = new ConcurrentHashMap<>();

    /** Milliseconds since this widget (on this hologram) last appeared on screen. */
    public static long sinceAppear(Object holoKey, String widgetId) {
        long now = System.currentTimeMillis();
        String key = holoKey + "|" + widgetId;
        long[] s = SEEN.get(key);
        if (s == null || now - s[1] > 500) {
            s = new long[] { now, now };
            SEEN.put(key, s);
            if (SEEN.size() > 4096) SEEN.clear();
        }
        s[1] = now;
        return now - s[0];
    }

    public Frame frame(long sinceAppearMs) {
        double t = System.currentTimeMillis() / 1000.0;
        double sinceAppear = Math.max(0, sinceAppearMs / 1000.0 - delay);
        float sp = Math.max(0.01f, speed);
        return switch (type) {
            case PULSE -> new Frame((float) (0.675 + 0.325 * Math.sin(t * sp * Math.PI * 2)), 0, 0, 0);
            case BLINK -> new Frame((t * sp) % 1.0 < 0.5 ? 1f : 0f, 0, 0, 0);
            case FADE_IN -> new Frame(ease(sinceAppear * sp), 0, 0, 0);
            case SLIDE_IN -> {
                float k = ease(sinceAppear * sp), off = (1 - k) * amount;
                yield switch (direction == null ? "left" : direction) {
                    case "right" -> new Frame(k, off, 0, 0);
                    case "up" -> new Frame(k, 0, -off, 0);
                    case "down" -> new Frame(k, 0, off, 0);
                    default -> new Frame(k, -off, 0, 0);
                };
            }
            case BOB -> new Frame(1f, 0, (float) (Math.sin(t * sp * Math.PI * 2) * amount * 0.5), 0);
            case SPIN -> new Frame(1f, 0, 0, (float) ((t * sp * 360.0) % 360.0));
            case TYPEWRITER -> sinceAppearMs < delay * 1000 ? new Frame(0f, 0, 0, 0) : Frame.IDENTITY;
            // Small rapid jitter.
            case SHAKE -> new Frame(1f, (float) (Math.sin(t * sp * 40) * amount * 0.25), (float) (Math.cos(t * sp * 33) * amount * 0.15), 0);
            // Pendulum: rotate back and forth by "amount" degrees.
            case SWING -> new Frame(1f, 0, 0, (float) (Math.sin(t * sp * Math.PI * 2) * amount));
            // Quick little tilt jiggle.
            case WOBBLE -> new Frame(1f, 0, 0, (float) (Math.sin(t * sp * 12) * Math.min(amount, 15) * 0.5));
            // Drifts in a slow loop.
            case FLOAT -> new Frame(1f, (float) (Math.cos(t * sp * Math.PI) * amount * 0.35), (float) (Math.sin(t * sp * Math.PI * 2) * amount * 0.5), 0);
            // Gently grows and shrinks ("amount" = percent).
            case BREATHE -> new Frame(1f, 0, 0, 0, 1f + (float) (Math.sin(t * sp * Math.PI * 2) * amount / 200.0), 1f);
            // Two quick beats then a pause.
            case HEARTBEAT -> {
                double ph = (t * sp) % 1.0;
                double beat = ph < 0.12 ? Math.sin(ph / 0.12 * Math.PI) : ph > 0.2 && ph < 0.32 ? Math.sin((ph - 0.2) / 0.12 * Math.PI) * 0.7 : 0;
                yield new Frame(1f, 0, 0, 0, 1f + (float) (beat * amount / 100.0), 1f);
            }
            // Appears with a springy overshoot.
            case POP_IN -> {
                double k = Math.min(1, sinceAppear * sp * 2);
                double s = k >= 1 ? 1 : 1 + 2.7 * Math.pow(k - 1, 3) + 1.7 * Math.pow(k - 1, 2); // ease-out-back
                yield new Frame((float) Math.min(1, k * 2), 0, 0, 0, (float) Math.max(0.01, s), 1f);
            }
            // Grows from nothing.
            case ZOOM_IN -> {
                float k = ease(sinceAppear * sp);
                yield new Frame(k, 0, 0, 0, Math.max(0.01f, k), 1f);
            }
            // Faulty-neon flicker.
            case FLICKER -> {
                long slot = (long) (t * sp * 12);
                double r = ((slot * 2654435761L) & 0xFFFF) / 65535.0;
                yield new Frame(r < 0.12 ? 0.2f : r < 0.2 ? 0.6f : 1f, 0, 0, 0);
            }
            // Brightness pulse ("amount" = percent).
            case GLOW -> new Frame(1f, 0, 0, 0, 1f, 1f + (float) ((Math.sin(t * sp * Math.PI * 2) + 1) / 2 * amount / 100.0));
            default -> Frame.IDENTITY;
        };
    }

    private static float ease(double k) {
        double c = Math.max(0, Math.min(1, k));
        return (float) (1 - Math.pow(1 - c, 3));
    }

    /** Text after scroll / typewriter / cycle. Rich tokens (icons, gradients) are left untouched. */
    public String text(String s, long sinceAppearMs) {
        if (s == null || s.isEmpty()) return s;
        switch (type) {
            case CYCLE -> {
                String[] frames = s.split("\\|\\|");
                if (frames.length <= 1) return s;
                int i = (int) ((long) Math.floor(System.currentTimeMillis() / 1000.0 * Math.max(0.01, speed)) % frames.length);
                return frames[Math.max(0, i)].trim();
            }
            case SCROLL, TYPEWRITER -> {
                if (s.contains(":#") || s.contains(":grad[")) return s;
                List<String[]> units = units(s); // {style-so-far, char}
                if (units.isEmpty()) return s;
                if (type == Type.SCROLL) {
                    int width = Math.max(1, Math.round(amount));
                    if (units.size() <= width) return s;
                    List<String[]> loop = new ArrayList<>(units);
                    for (int i = 0; i < 3; i++) loop.add(new String[] { "", " " });
                    int start = (int) ((long) Math.floor(System.currentTimeMillis() / 1000.0 * Math.max(0.01, speed)) % loop.size());
                    List<String[]> window = new ArrayList<>();
                    for (int i = 0; i < width; i++) window.add(loop.get((start + i) % loop.size()));
                    return join(window);
                }
                double secs = Math.max(0, sinceAppearMs / 1000.0 - delay);
                int total = units.size();
                double cycle = total / Math.max(0.01, speed) + 2.0; // pause 2s when fully typed, then restart
                int shown = (int) Math.min(total, Math.floor((secs % cycle) * Math.max(0.01, speed)));
                return join(units.subList(0, shown));
            }
            default -> { return s; }
        }
    }

    /** Characters back into text, re-applying the style wherever it changes (and §r before plain gap spaces). */
    private static String join(List<String[]> units) {
        StringBuilder out = new StringBuilder();
        String current = null;
        for (String[] u : units) {
            if (!u[0].equals(current)) {
                if (u[0].isEmpty() && current != null) out.append("§r");
                out.append(u[0]);
                current = u[0];
            }
            out.append(u[1]);
        }
        return out.toString();
    }

    /**
     * Visible characters with the § formatting active at each one ("style so far"), so a window that
     * starts mid-string still has the right colour.
     */
    private static List<String[]> units(String s) {
        List<String[]> out = new ArrayList<>();
        String color = "";
        StringBuilder formats = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == '#' && i + 6 < s.length() && com.luminacity.advancedinteractions.util.TextFmt.isHex(s, i + 1)) {
                    color = "§#" + s.substring(i + 1, i + 7);
                    formats.setLength(0);
                    i += 6;
                    continue;
                }
                if ("0123456789abcdef".indexOf(code) >= 0 || code == 'r') { color = "§" + code; formats.setLength(0); }
                else formats.append('§').append(code);
                continue;
            }
            out.add(new String[] { color + formats, String.valueOf(c) });
        }
        return out;
    }

    /** Rainbow colour (keeps alpha), or the colour unchanged. */
    public int color(int argb) {
        if (type != Type.RAINBOW) return argb;
        float h = (float) ((System.currentTimeMillis() / 1000.0 * Math.max(0.01, speed * 0.25)) % 1.0);
        int rgb = hsv(h, 0.8f, 1f);
        int a = (argb >>> 24) & 0xFF;
        return ((a == 0 ? 0xFF : a) << 24) | rgb;
    }

    private static int hsv(float h, float s, float v) {
        int i = (int) Math.floor(h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    // ---------------------------------------------------------------- NBT

    public void saveTo(CompoundTag parent) {
        if (type == Type.NONE) return;
        CompoundTag t = new CompoundTag();
        t.putString("type", type.name().toLowerCase(Locale.ROOT));
        t.putFloat("speed", speed);
        t.putFloat("amount", amount);
        if (!"left".equals(direction)) t.putString("dir", direction);
        if (onHover) t.putBoolean("hover", true);
        if (delay != 0) t.putFloat("delay", delay);
        parent.put("anim", t);
    }

    public static WidgetAnim load(CompoundTag parent) {
        WidgetAnim a = new WidgetAnim();
        if (parent == null || !parent.contains("anim")) return a;
        CompoundTag t = parent.getCompound("anim");
        a.type = parse(t.getString("type"));
        if (t.contains("speed")) a.speed = t.getFloat("speed");
        if (t.contains("amount")) a.amount = t.getFloat("amount");
        a.delay = t.getFloat("delay");
        if (t.contains("dir")) a.direction = t.getString("dir");
        a.onHover = t.getBoolean("hover");
        return a;
    }
}
