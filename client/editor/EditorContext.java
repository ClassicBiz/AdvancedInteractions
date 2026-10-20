package com.luminacity.advancedinteractions.client.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server-provided choices for the editor: permissions, templates, actions, placeholders, filters. */
public final class EditorContext {
    public record TemplateInfo(String id, boolean snapshot, String info) {}

    public boolean canConsole;
    /** Editing your own hologram as a player: limits apply. */
    public boolean restricted;
    public String limits = "";
    public String storedDim = "";
    public String command = "aholo";
    public final List<TemplateInfo> templates = new ArrayList<>();
    public final List<String> actions = new ArrayList<>();
    public final List<String> prefixes = new ArrayList<>();
    public final Map<String, Map<String, String>> placeholders = new TreeMap<>();
    public final Map<String, String> filters = new LinkedHashMap<>();
    public final List<String> images = new ArrayList<>();
    public final List<String> conditions = new ArrayList<>();
    /** Server button style presets: name -> {style, color, hover}. */
    public final Map<String, CompoundTag> buttonStyles = new TreeMap<>();
    /** Shops on the server for advholo:trade (id -> description). */
    public final Map<String, String> shops = new TreeMap<>();

    public static EditorContext from(CompoundTag c) {
        EditorContext e = new EditorContext();
        if (c == null) return e;
        e.canConsole = c.getBoolean("canConsole");
        e.restricted = c.getBoolean("restricted");
        e.limits = c.getString("limits");
        e.storedDim = c.getString("storedDim");
        if (c.contains("command")) e.command = c.getString("command");
        ListTag t = c.getList("templates", Tag.TAG_COMPOUND);
        for (int i = 0; i < t.size(); i++) {
            CompoundTag x = t.getCompound(i);
            e.templates.add(new TemplateInfo(x.getString("id"), x.getBoolean("snapshot"), x.getString("info")));
        }
        ListTag a = c.getList("actions", Tag.TAG_STRING);
        for (int i = 0; i < a.size(); i++) e.actions.add(a.getString(i));
        ListTag pf = c.getList("prefixes", Tag.TAG_STRING);
        for (int i = 0; i < pf.size(); i++) e.prefixes.add(pf.getString(i));
        CompoundTag ph = c.getCompound("placeholders");
        for (String ns : ph.getAllKeys()) {
            CompoundTag keys = ph.getCompound(ns);
            Map<String, String> m = new TreeMap<>();
            for (String k : keys.getAllKeys()) m.put(k, keys.getString(k));
            e.placeholders.put(ns, m);
        }
        ListTag im = c.getList("images", Tag.TAG_STRING);
        for (int i = 0; i < im.size(); i++) e.images.add(im.getString(i));
        ListTag cn = c.getList("conditions", Tag.TAG_STRING);
        for (int i = 0; i < cn.size(); i++) e.conditions.add(cn.getString(i));
        CompoundTag sh = c.getCompound("shops");
        for (String k : sh.getAllKeys()) e.shops.put(k, sh.getString(k));
        CompoundTag bs = c.getCompound("buttonStyles");
        for (String k : bs.getAllKeys()) e.buttonStyles.put(k, bs.getCompound(k));
        CompoundTag f = c.getCompound("filters");
        new TreeMap<>(f.getAllKeys().stream().collect(java.util.stream.Collectors.toMap(k -> k, f::getString)))
                .forEach(e.filters::put);
        return e;
    }
}
