package com.luminacity.advancedinteractions.templates;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luminacity.advancedinteractions.api.ActionRegistry;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.PanelStyle;
import com.luminacity.advancedinteractions.panel.PanelUnits;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.panel.ShapeWidget;
import com.luminacity.advancedinteractions.panel.TextWidget;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hologram layout templates (config/advancedinteractions/templates/*.json).
 *
 * <p>Format (see the bundled card_generic.json):</p>
 * <pre>
 * {
 *   "id": "card_generic",
 *   "style": "panel",            // only "panel" for now
 *   "bg": "#22000000",           // optional ARGB background
 *   "autoFit": true,             // fit rows to content height
 *   "width": 180, "height": 80,  // panel pixels
 *   "elements": [ { "type": "text", "value": "...", "align": "center", "visibleIf": "has(price)",
 *                   "scale": 1.0, "color": "#FFFFFFFF" },
 *                 { "type": "spacer", "height": 6 },
 *                 { "type": "shape", "shape": "rrect", "x": 0, "y": 0, "w": 180, "h": 2, "color": "#FFFFFFFF" } ],
 *   "buttons":  [ { "id": "{cta_action}", "label": "...", "visibleIf": "has(cta_action)" } ]
 * }
 * </pre>
 *
 * <p>Template variables like {name} resolve live through {data.name} (the hologram's data binding,
 * then its meta). {@code <grad:#a:#b>text</grad>} becomes the renderer's {@code :grad[#a-#b]text:}.
 * visibleIf conditions are evaluated live per viewer.</p>
 *
 * <p>Snapshot templates ({@code "kind": "snapshot"}) are written by {@code /aholo <id> template save <name>}:
 * the hologram's full layout (widgets, pages, menus, views, locks, style, meta) as SNBT, without its
 * identity or placement. Applying one replaces the target's layout and keeps where it is. The file is
 * self-contained, so sharing it is just copying it into another server's templates folder.</p>
 */
public final class TemplateManager {
    private TemplateManager() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] BUNDLED = { "card_generic.json" };
    private static final Pattern GRAD = Pattern.compile("<grad:([^:>]+):([^>]+)>(.*?)</grad>", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("[a-z0-9_\\-]{1,48}");
    public static final String KIND_SNAPSHOT = "snapshot";

    /** Which hologram this is and where it stands; never stored in or taken from a snapshot. */
    private static final Set<String> PLACEMENT_KEYS = Set.of(
            "id", "alias", "anchor", "anchorEntity", "privateToEntity", "billboard", "fixedYawDeg", "anchorFacing",
            "rotationOffsetDeg", "x", "y", "z", "offX", "offY", "offZ", "dim", "templateId", "editView",
            "currentPage", "currentMenu",
            // Who owns it, staff state and the blocks it's linked to belong to the hologram, not the design
            // (otherwise applying a template would hand it to the template's author and their chest).
            "owner", "locked", "hidden", "links");

    private static final Map<String, JsonObject> TEMPLATES = new LinkedHashMap<>();
    private static final Map<String, Path> FILES = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("advancedinteractions").resolve("templates");
    }

    /** (Re)load all templates from disk, copying bundled defaults on first run. Returns the count. */
    public static synchronized int reload() {
        TEMPLATES.clear();
        FILES.clear();
        Path dir = dir();
        try {
            Files.createDirectories(dir);
            for (String name : BUNDLED) {
                Path target = dir.resolve(name);
                if (Files.exists(target)) continue;
                try (InputStream in = TemplateManager.class.getResourceAsStream("/config/advancedinteractions/templates/" + name)) {
                    if (in != null) Files.copy(in, target);
                }
            }
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                    try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                        JsonElement el = JsonParser.parseReader(r);
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String id = o.has("id") ? o.get("id").getAsString() : stripExt(p.getFileName().toString());
                        TEMPLATES.put(id.toLowerCase(Locale.ROOT), o);
                        FILES.put(id.toLowerCase(Locale.ROOT), p);
                    } catch (Exception e) {
                        LOGGER.warn("[AdvancedInteractions] Bad template {}: {}", p.getFileName(), e.toString());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[AdvancedInteractions] Could not load templates from {}: {}", dir, e.toString());
        }
        loaded = true;
        return TEMPLATES.size();
    }

    public static synchronized List<String> ids() {
        if (!loaded) reload();
        List<String> out = new ArrayList<>(TEMPLATES.keySet());
        Collections.sort(out);
        return out;
    }

    public static synchronized boolean exists(String id) {
        if (!loaded) reload();
        return id != null && TEMPLATES.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public static synchronized boolean isSnapshot(String id) {
        if (!loaded) reload();
        JsonObject t = id == null ? null : TEMPLATES.get(id.toLowerCase(Locale.ROOT));
        return t != null && KIND_SNAPSHOT.equalsIgnoreCase(strOr(t, "kind", ""));
    }

    /** One-line summary for /aholo templates info. */
    public static synchronized String info(String id) {
        if (!loaded) reload();
        JsonObject t = id == null ? null : TEMPLATES.get(id.toLowerCase(Locale.ROOT));
        if (t == null) return null;
        String kind = strOr(t, "kind", "layout");
        String desc = strOr(t, "description", "");
        String author = strOr(t, "author", "");
        Path file = FILES.get(id.toLowerCase(Locale.ROOT));
        return id.toLowerCase(Locale.ROOT) + " [" + kind + "]"
                + (desc.isEmpty() ? "" : " - " + desc)
                + (author.isEmpty() ? "" : " (by " + author + ")")
                + (file == null ? "" : " file: " + file.getFileName());
    }

    /**
     * Save a hologram's whole layout as a snapshot template file.
     *
     * @return null on success, or an error message
     */
    public static synchronized String save(HologramDef def, String name, String author, String description, boolean replace) {
        if (!loaded) reload();
        String id = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        if (!NAME.matcher(id).matches()) return "Template names may only use a-z, 0-9, _ and - (max 48).";
        if (TEMPLATES.containsKey(id) && !replace) {
            return "Template '" + id + "' already exists. Add 'replace' to overwrite it.";
        }

        CompoundTag tag = def.save();
        for (String k : PLACEMENT_KEYS) tag.remove(k);

        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", KIND_SNAPSHOT);
        o.addProperty("format", 1);
        if (description != null && !description.isBlank()) o.addProperty("description", description.trim());
        if (author != null && !author.isBlank()) o.addProperty("author", author);
        o.addProperty("created", java.time.LocalDate.now().toString());
        o.addProperty("hologram", tag.toString());

        Path file = FILES.getOrDefault(id, dir().resolve(id + ".json"));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AdvancedInteractions] Could not write template {}: {}", file, e.toString());
            return "Could not write " + file.getFileName() + ": " + e.getMessage();
        }
        TEMPLATES.put(id, o);
        FILES.put(id, file);
        return null;
    }

    /** Delete a template file. @return null on success, or an error message */
    public static synchronized String delete(String name) {
        if (!loaded) reload();
        String id = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        Path file = FILES.get(id);
        if (file == null) return "Unknown template: " + id;
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            return "Could not delete " + file.getFileName() + ": " + e.getMessage();
        }
        TEMPLATES.remove(id);
        FILES.remove(id);
        return null;
    }

    public record Built(HologramDef def, String error) {}

    /**
     * Build the hologram that applying a snapshot template to {@code def} produces: the template's layout
     * with {@code def}'s identity and placement. The caller stores the result in place of {@code def}.
     */
    public static synchronized Built buildFromSnapshot(HologramDef def, String templateId) {
        if (!loaded) reload();
        JsonObject t = templateId == null ? null : TEMPLATES.get(templateId.toLowerCase(Locale.ROOT));
        if (t == null) return new Built(null, "Unknown template: " + templateId);
        return buildFromSnapshotJson(t, def, templateId);
    }

    /** The template file's JSON (a copy), for the editor to apply client-side; null if unknown. */
    public static synchronized JsonObject json(String templateId) {
        if (!loaded) reload();
        JsonObject t = templateId == null ? null : TEMPLATES.get(templateId.toLowerCase(Locale.ROOT));
        return t == null ? null : t.deepCopy();
    }

    public static boolean isSnapshotJson(JsonObject t) {
        return t != null && KIND_SNAPSHOT.equalsIgnoreCase(strOr(t, "kind", ""));
    }

    /** Snapshot apply from template JSON (works on either side; the editor uses it client-side). */
    public static Built buildFromSnapshotJson(JsonObject t, HologramDef def, String templateId) {
        CompoundTag layout;
        try {
            layout = TagParser.parseTag(strOr(t, "hologram", "{}"));
        } catch (Exception e) {
            return new Built(null, "Template '" + templateId + "' is damaged: " + e.getMessage());
        }
        for (String k : PLACEMENT_KEYS) layout.remove(k);

        CompoundTag merged = new CompoundTag();
        CompoundTag current = def.save();
        for (String k : PLACEMENT_KEYS) {
            if (current.contains(k)) merged.put(k, current.get(k).copy());
        }
        merged.merge(layout);
        merged.putString("templateId", templateId.toLowerCase(Locale.ROOT));
        try {
            return new Built(HologramDef.load(merged), null);
        } catch (Exception e) {
            return new Built(null, "Template '" + templateId + "' could not be loaded: " + e.getMessage());
        }
    }

    /** True if any action in the template runs as console (applying it then needs console-action rights). */
    public static synchronized boolean hasConsoleAction(String templateId) {
        if (!loaded) reload();
        JsonObject t = templateId == null ? null : TEMPLATES.get(templateId.toLowerCase(Locale.ROOT));
        if (t == null) return false;
        if (KIND_SNAPSHOT.equalsIgnoreCase(strOr(t, "kind", ""))) {
            // Actions are plain strings in the NBT; matching the text is conservative (a label saying
            // "console:" also counts), which is the safe direction.
            return strOr(t, "hologram", "").toLowerCase(Locale.ROOT).contains(ActionRegistry.CONSOLE_PREFIX);
        }
        JsonArray buttons = t.has("buttons") && t.get("buttons").isJsonArray() ? t.getAsJsonArray("buttons") : new JsonArray();
        for (JsonElement el : buttons) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            if (ActionRegistry.isConsoleAction(strOr(e, "action", strOr(e, "id", "")))) return true;
        }
        return false;
    }

    /**
     * Replace the given widget list with the template's layout and size the panel.
     *
     * @return null on success, or an error message
     */
    public static synchronized String apply(HologramDef def, List<PanelWidget> target, String templateId) {
        if (!loaded) reload();
        if (def == null || target == null || templateId == null) return "No target.";
        JsonObject t = TEMPLATES.get(templateId.toLowerCase(Locale.ROOT));
        if (t == null) return "Unknown template: " + templateId;
        return applyJson(t, def, target, templateId);
    }

    /** Layout-template apply from JSON (works on either side; the editor uses it client-side). */
    public static String applyJson(JsonObject t, HologramDef def, List<PanelWidget> target, String templateId) {
        if (def == null || target == null || t == null) return "No target.";

        int widthPx = intOr(t, "width", def.cols * PanelUnits.COL_PX);
        int heightPx = intOr(t, "height", def.rows * PanelUnits.ROW_PX);
        int cols = Math.max(4, (int) Math.ceil(widthPx / (double) PanelUnits.COL_PX));
        int contentW = Math.max(cols * PanelUnits.COL_PX, 64);

        List<PanelWidget> out = new ArrayList<>();
        double y = 4;

        JsonArray elements = t.has("elements") && t.get("elements").isJsonArray() ? t.getAsJsonArray("elements") : new JsonArray();
        int i = 0;
        for (JsonElement el : elements) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String type = strOr(e, "type", "text").toLowerCase(Locale.ROOT);
            String cond = strOr(e, "visibleIf", "");
            switch (type) {
                case "spacer" -> y += intOr(e, "height", 6);
                case "shape" -> {
                    ShapeWidget sw = new ShapeWidget("tpl_shape_" + i, ShapeWidget.Type.from(strOr(e, "shape", "rect")));
                    sw.setPos(dblOr(e, "x", 0), dblOr(e, "y", y));
                    sw.setSize(dblOr(e, "w", contentW), dblOr(e, "h", 2));
                    Integer c = parseArgb(strOr(e, "color", ""));
                    if (c != null) sw.setFill(c);
                    sw.setLayer(intOr(e, "layer", 0));
                    sw.setCondition(cond);
                    out.add(sw);
                    if (!e.has("y")) y += dblOr(e, "h", 2) + 2;
                }
                default -> {
                    String align = strOr(e, "align", "left").toLowerCase(Locale.ROOT);
                    float scale = (float) dblOr(e, "scale", 1.0);
                    double x = align.startsWith("center") || align.equals("middle") ? contentW / 2.0
                            : align.equals("right") ? contentW - 4 : 4;
                    TextWidget tw = new TextWidget("tpl_text_" + i, x, y, convertText(strOr(e, "value", "")));
                    tw.setAlign(align);
                    tw.setScale(scale);
                    Integer c = parseArgb(strOr(e, "color", ""));
                    if (c != null) tw.setColor(c);
                    tw.setCondition(cond);
                    out.add(tw);
                    y += 11 * Math.max(0.25, scale);
                }
            }
            i++;
        }

        JsonArray buttons = t.has("buttons") && t.get("buttons").isJsonArray() ? t.getAsJsonArray("buttons") : new JsonArray();
        int b = 0;
        if (buttons.size() > 0) y += 2;
        for (JsonElement el : buttons) {
            if (!el.isJsonObject()) continue;
            JsonObject e = el.getAsJsonObject();
            String action = strOr(e, "action", strOr(e, "id", ""));
            ButtonWidget bw = new ButtonWidget("tpl_btn_" + b, 8, y, contentW - 16, 14, action, convertText(strOr(e, "label", "")));
            Integer c = parseArgb(strOr(e, "color", ""));
            bw.setColor(c != null ? c : 0x00000000);
            Integer hc = parseArgb(strOr(e, "hover", ""));
            bw.setColorHover(hc != null ? hc : 0x00000000);
            bw.setCondition(strOr(e, "visibleIf", ""));
            out.add(bw);
            y += 16;
            b++;
        }

        boolean autoFit = t.has("autoFit") && t.get("autoFit").getAsBoolean();
        int rows = autoFit
                ? Math.max(2, (int) Math.ceil((y + 4) / PanelUnits.ROW_PX))
                : Math.max(2, (int) Math.ceil(heightPx / (double) PanelUnits.ROW_PX));

        Integer bg = parseArgb(strOr(t, "bg", ""));
        if (bg != null) {
            if (def.panelStyle == null) def.panelStyle = new PanelStyle();
            def.panelStyle.bg = bg;
        }

        target.clear();
        target.addAll(out);
        def.cols = cols;
        def.rows = rows;
        def.templateId = templateId.toLowerCase(Locale.ROOT);
        return null;
    }

    /** Template text -> renderer rich text. */
    public static String convertText(String raw) {
        if (raw == null) return "";
        Matcher m = GRAD.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(3).replace("\\", "\\\\").replace(":", "\\:");
            m.appendReplacement(sb, Matcher.quoteReplacement(":grad[" + m.group(1).trim() + "-" + m.group(2).trim() + "]" + inner + ":"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Integer parseArgb(String s) {
        if (s == null || s.isBlank()) return null;
        String h = s.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
            if (h.length() == 8) return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String strOr(JsonObject o, String k, String def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; } catch (Exception e) { return def; }
    }

    private static int intOr(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    private static double dblOr(JsonObject o, String k, double def) {
        try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; }
    }
}
