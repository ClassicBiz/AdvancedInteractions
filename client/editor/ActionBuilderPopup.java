package com.luminacity.advancedinteractions.client.editor;

import com.luminacity.advancedinteractions.client.editor.ui.Field;
import com.luminacity.advancedinteractions.client.editor.ui.Focus;
import com.luminacity.advancedinteractions.client.editor.ui.Form;
import com.luminacity.advancedinteractions.client.editor.ui.MiniButton;
import com.luminacity.advancedinteractions.client.editor.ui.Popup;
import com.luminacity.advancedinteractions.client.editor.ui.Rows;
import com.luminacity.advancedinteractions.client.editor.ui.Theme;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds an action string from a list of choices ("Go to page", "Toggle a player variable", "Run a
 * console command"...), so nobody has to remember advholo:...?key=... syntax. Opens on the
 * current action, parsed back into its type and fields.
 */
public class ActionBuilderPopup extends Popup {

    private enum Type {
        NONE("Nothing"), NEXT("Next page"), PREV("Previous page"), PAGE("Go to page"),
        MENU_OPEN("Open a menu"), MENU_TOGGLE("Toggle a menu"), MENU_CLOSE("Close the menu"),
        STATE_TOGGLE("Toggle a player variable"), STATE_SET("Set a player variable"),
        STATE_ADD("Add to a player variable"), STATE_CLEAR("Clear a player variable"),
        SHOW("Show an element"), HIDE("Hide an element"), TOGGLE_SHOW("Show/hide an element"),
        VAR_SET("Set a variable"), VAR_ADD("Add to a variable"), VAR_TOGGLE("Toggle a variable"),
        EVENT("Fire an event (for mods/scripts)"), TRADE("Buy / sell (shop)"),
        SOUND("Play a sound (to the player)"), MESSAGE("Chat message (to the player)"), ACTIONBAR("Action bar text"),
        PLAYER_CMD("Run a command as the player"), CONSOLE_CMD("Run a command as console"),
        REGISTERED("Mod action"), RAW("Custom (type it yourself)");

        final String label;
        Type(String label) { this.label = label; }
    }

    private final EditorSession session;
    private final Consumer<String> onDone;
    private Type type = Type.NONE;
    private final Map<String, String> v = new HashMap<>();
    private final Form types = new Form();
    private final Form fields = new Form();
    private final Field preview;
    private final MiniButton ok = new MiniButton("Use this action", this::done).style(MiniButton.Style.ACCENT);
    private final MiniButton cancel = new MiniButton("Cancel", this::close);

    public ActionBuilderPopup(EditorSession session, String current, Consumer<String> onDone) {
        super("Button action", 460, 300);
        this.session = session;
        this.onDone = onDone;
        parse(current == null ? "" : current.trim());
        this.preview = new Field(4096, this::build, s -> { type = Type.RAW; v.put("raw", s); rebuildFields(); });
        types.background = Theme.PANEL_ALT;
        fields.background = 0;
        rebuildTypes();
        rebuildFields();
    }

    // ---------------------------------------------------------------- parse / build

    private static Map<String, String> query(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int q = s.indexOf('?');
        if (q < 0) return m;
        for (String pair : com.luminacity.advancedinteractions.util.QueryParams.split(s.substring(q + 1))) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return m;
    }

    private void parse(String a) {
        String low = a.toLowerCase();
        Map<String, String> q = query(a);
        v.putAll(q);
        if (a.isEmpty()) { type = Type.NONE; return; }
        if (low.startsWith("advholo:")) {
            String id = low.substring(8).split("\\?")[0];
            switch (id) {
                case "page_next" -> type = Type.NEXT;
                case "page_prev" -> type = Type.PREV;
                case "page_set" -> { type = Type.PAGE; v.put("page", q.getOrDefault("page", q.getOrDefault("p", "1"))); }
                case "menu_open" -> { type = Type.MENU_OPEN; v.put("name", q.getOrDefault("name", q.getOrDefault("menu", ""))); }
                case "menu_toggle" -> { type = Type.MENU_TOGGLE; v.put("name", q.getOrDefault("name", q.getOrDefault("menu", ""))); }
                case "menu_close", "menu_back", "menu_main" -> type = Type.MENU_CLOSE;
                case "state_toggle" -> type = Type.STATE_TOGGLE;
                case "state_set" -> type = Type.STATE_SET;
                case "state_add" -> type = Type.STATE_ADD;
                case "state_clear" -> type = Type.STATE_CLEAR;
                case "var_set" -> type = Type.VAR_SET;
                case "var_add" -> type = Type.VAR_ADD;
                case "var_toggle" -> type = Type.VAR_TOGGLE;
                case "event" -> type = Type.EVENT;
                case "trade" -> type = Type.TRADE;
                case "show" -> type = Type.SHOW;
                case "hide" -> type = Type.HIDE;
                case "toggle_show" -> type = Type.TOGGLE_SHOW;
                case "sound" -> type = Type.SOUND;
                case "message" -> type = Type.MESSAGE;
                case "actionbar" -> type = Type.ACTIONBAR;
                default -> { type = Type.RAW; v.put("raw", a); }
            }
            return;
        }
        if (low.startsWith("console:")) { type = Type.CONSOLE_CMD; v.put("command", a.substring(8).trim()); return; }
        if (low.startsWith("cmd:")) { type = Type.PLAYER_CMD; v.put("command", a.substring(4).trim()); return; }
        if (low.startsWith("player:")) { type = Type.PLAYER_CMD; v.put("command", a.substring(7).trim()); return; }
        String base = a.split("\\?")[0];
        if (session.ctx.actions.contains(base)) {
            type = Type.REGISTERED;
            v.put("id", base);
            v.put("params", a.contains("?") ? a.substring(a.indexOf('?') + 1) : "");
            return;
        }
        if (a.startsWith("/")) { type = Type.PLAYER_CMD; v.put("command", a); return; }
        type = Type.RAW;
        v.put("raw", a);
    }

    private String scope() {
        return ("global".equalsIgnoreCase(g("scope", "player")) ? "&scope=global" : "")
                + ("true".equalsIgnoreCase(g("temp", "false")) ? "&temp=true" : "");
    }

    private String g(String k, String def) { String s = v.get(k); return s == null || s.isEmpty() ? def : s; }

    private String build() {
        return switch (type) {
            case NONE -> "";
            case NEXT -> "advholo:page_next";
            case PREV -> "advholo:page_prev";
            case PAGE -> "advholo:page_set?page=" + g("page", "1");
            case MENU_OPEN -> "advholo:menu_open?name=" + g("name", "");
            case MENU_TOGGLE -> "advholo:menu_toggle?name=" + g("name", "");
            case MENU_CLOSE -> "advholo:menu_close";
            case STATE_TOGGLE -> "advholo:state_toggle?key=" + g("key", "");
            case STATE_SET -> "advholo:state_set?key=" + g("key", "") + "&value=" + g("value", "");
            case STATE_ADD -> "advholo:state_add?key=" + g("key", "") + "&amount=" + g("amount", "1");
            case STATE_CLEAR -> "advholo:state_clear?key=" + g("key", "");
            case VAR_SET -> "advholo:var_set?key=" + g("key", "") + "&value=" + g("value", "") + scope();
            case VAR_ADD -> "advholo:var_add?key=" + g("key", "") + "&amount=" + g("amount", "1") + scope();
            case VAR_TOGGLE -> "advholo:var_toggle?key=" + g("key", "") + scope();
            case EVENT -> "advholo:event?name=" + g("name", "") + (g("extra", "").isEmpty() ? "" : "&" + g("extra", ""));
            case TRADE -> "advholo:trade?side=" + g("side", "buy") + "&qty=" + g("qty", "1")
                    + (g("shop", "").isEmpty() ? "" : "&shop=" + g("shop", ""))
                    + (g("link", "").isEmpty() ? "" : "&link=" + g("link", ""))
                    + (g("item", "").isEmpty() ? "" : "&item=" + g("item", ""))
                    + (g("price", "").isEmpty() ? "" : "&price=" + g("price", ""));
            case SHOW, HIDE, TOGGLE_SHOW -> "advholo:" + (type == Type.SHOW ? "show" : type == Type.HIDE ? "hide" : "toggle_show")
                    + "?id=" + g("id", "") + (g("seconds", "0").equals("0") ? "" : "&seconds=" + g("seconds", "0"));
            case SOUND -> "advholo:sound?id=" + g("id", "minecraft:entity.experience_orb.pickup")
                    + "&volume=" + g("volume", "1") + "&pitch=" + g("pitch", "1");
            case MESSAGE -> "advholo:message?text=" + g("text", "");
            case ACTIONBAR -> "advholo:actionbar?text=" + g("text", "");
            case PLAYER_CMD -> "cmd:" + withSlash(g("command", ""));
            case CONSOLE_CMD -> "console:" + withSlash(g("command", ""));
            case REGISTERED -> g("id", "") + (g("params", "").isEmpty() ? "" : "?" + g("params", ""));
            case RAW -> g("raw", "");
        };
    }

    private static String withSlash(String c) { return c.isEmpty() || c.startsWith("/") ? c : "/" + c; }

    // ---------------------------------------------------------------- forms

    private void rebuildTypes() {
        types.clear();
        for (Type t : Type.values()) {
            if (t == Type.CONSOLE_CMD && !session.ctx.canConsole && type != Type.CONSOLE_CMD) continue;
            if (t == Type.REGISTERED && session.ctx.actions.isEmpty()) continue;
            types.add(Rows.item(() -> t.label, () -> type == t, () -> { type = t; rebuildFields(); }));
        }
    }

    private Rows.TextRow field(String label, String key, String def) {
        if (!v.containsKey(key)) v.put(key, def);
        return Rows.text(label, () -> v.getOrDefault(key, ""), s -> v.put(key, s));
    }

    private void rebuildFields() {
        fields.clear();
        switch (type) {
            case NONE -> fields.add(Rows.hint("The button does nothing when clicked (useful for labels)."));
            case NEXT, PREV -> fields.add(Rows.hint("Moves this player to the next/previous page they're allowed to open. Wraps around."));
            case PAGE -> {
                List<String> pages = new ArrayList<>();
                for (int i = 1; i <= session.def.pageCount(); i++) pages.add(Integer.toString(i));
                if (!v.containsKey("page")) v.put("page", "1");
                fields.add(Rows.choice("Page", () -> pages, () -> v.get("page"), s -> v.put("page", s)));
            }
            case MENU_OPEN, MENU_TOGGLE -> {
                List<String> menus = new ArrayList<>(session.def.menus.keySet());
                menus.sort(String::compareTo);
                if (menus.isEmpty()) fields.add(Rows.hint("This hologram has no menus yet. Add one on the Menus tab."));
                if (!v.containsKey("name")) v.put("name", menus.isEmpty() ? "" : menus.get(0));
                fields.add(Rows.choice("Menu", () -> menus, () -> v.get("name"), s -> v.put("name", s)));
                fields.add(Rows.hint("A menu replaces the panel's content for this player only."));
            }
            case MENU_CLOSE -> fields.add(Rows.hint("Closes the open menu and returns to the pages."));
            case STATE_TOGGLE, STATE_CLEAR -> {
                fields.add(field("Variable", "key", "my_toggle"));
                fields.add(Rows.hint("Read it with {state.KEY}, e.g. a label {state.KEY|if:§aON:§cOFF} or a condition eq(state.KEY,true). Saved per player."));
            }
            case STATE_SET -> { fields.add(field("Variable", "key", "my_var")); fields.add(field("Value", "value", "")); }
            case STATE_ADD -> { fields.add(field("Variable", "key", "clicks")); fields.add(field("Amount", "amount", "1")); }
            case SOUND -> {
                if (!v.containsKey("id")) v.put("id", "minecraft:entity.experience_orb.pickup");
                fields.add(Rows.text("Sound", () -> v.get("id"), s -> v.put("id", s))
                        .button("...", "Pick a sound", f -> Pickers.sounds(v.get("id"), s -> v.put("id", s))));
                fields.add(field("Volume", "volume", "1"));
                fields.add(field("Pitch", "pitch", "1"));
            }
            case MESSAGE, ACTIONBAR -> {
                fields.add(field("Text", "text", "&aHello {player.name}!"));
                fields.add(Rows.hint("Placeholders and &a / &#RRGGBB colour codes work."));
            }
            case VAR_SET, VAR_ADD, VAR_TOGGLE -> {
                fields.add(field("Variable", "key", "coins"));
                if (type == Type.VAR_SET) fields.add(field("Value", "value", "1"));
                if (type == Type.VAR_ADD) fields.add(field("Amount (negative subtracts)", "amount", "1"));
                fields.add(field("Scope (player / global)", "scope", "player"));
                fields.add(field("Temporary (true / false)", "temp", "false"));
                fields.add(Rows.hint("Read it with {var.NAME}. Temporary variables reset when the server restarts."));
            }
            case EVENT -> {
                fields.add(field("Event name", "name", "opened_shop"));
                fields.add(field("Extra params (a=1&b=2)", "extra", ""));
                fields.add(Rows.hint("Posts HologramActionEvent so another mod or a KubeJS script can react."));
            }
            case TRADE -> {
                fields.add(field("buy or sell", "side", "buy"));
                fields.add(field("Quantity", "qty", "{input.qty}"));
                fields.add(field("Shop (blank = built-in)", "shop", "")
                        .button("...", "Shops on this server", f -> {
                            java.util.List<com.luminacity.advancedinteractions.client.editor.ui.PickerPopup.Entry> list = new java.util.ArrayList<>();
                            session.ctx.shops.forEach((id, desc) -> list.add(new com.luminacity.advancedinteractions.client.editor.ui.PickerPopup.Entry(id, desc, id)));
                            com.luminacity.advancedinteractions.client.editor.ui.UiHost.open(new com.luminacity.advancedinteractions.client.editor.ui.PickerPopup("Shop", list, null, id -> v.put("shop", id)));
                        }));
                fields.add(field("Link (shop block)", "link", ""));
                fields.add(field("Item (built-in shop)", "item", "minecraft:diamond"));
                fields.add(field("Price each (built-in shop)", "price", "10"));
                fields.add(Rows.hint("Pick a shop with '...'. LuminShops: link its sign (or chest) in the Interaction tab and put "
                        + "that link's name in Link; item and price come from the sign. The built-in shop needs Item and Price."));
            }
            case SHOW, HIDE, TOGGLE_SHOW -> {
                fields.add(field("Element id(s)", "id", ""));
                fields.add(field("For seconds (0 = until changed)", "seconds", "0"));
                fields.add(Rows.hint("Only for the clicking player. Separate several ids with commas. "
                        + "Pair with \"Start hidden\" (Who sees it) for reveal-on-click panels."));
            }
            case PLAYER_CMD -> {
                fields.add(field("Command", "command", "/spawn"));
                fields.add(Rows.hint("Runs with the clicking player's own permissions. Placeholders work, e.g. /warp {data.warp}."));
            }
            case CONSOLE_CMD -> {
                fields.add(field("Command", "command", "/give {player.name} minecraft:diamond 1"));
                fields.add(Rows.hint("Runs as the server with full permissions. Only {player.name/uuid/x/y/z}, {holo.*}, {pos.*} "
                        + "and {world.id/name} are allowed in console commands."));
                if (!session.ctx.canConsole) fields.add(Rows.hint(() -> "You can keep this existing console action, but you can't change it (needs op 4).", Theme.WARN));
            }
            case REGISTERED -> {
                List<String> ids = session.ctx.actions;
                if (!v.containsKey("id")) v.put("id", ids.isEmpty() ? "" : ids.get(0));
                fields.add(Rows.choice("Action", () -> ids, () -> v.get("id"), s -> v.put("id", s)));
                fields.add(field("Parameters", "params", ""));
                fields.add(Rows.hint("Parameters as key=value&key2=value2, if the action takes any."));
            }
            case RAW -> {
                fields.add(field("Action", "raw", ""));
                fields.add(Rows.hint("Any action: advholo:..., cmd:/..., console:/..., or a mod's action id."));
            }
        }
        rebuildTypes();
    }

    private void done() {
        String a = build();
        close();
        onDone.accept(a);
    }

    @Override
    protected void onEnter() { done(); }

    // ---------------------------------------------------------------- layout & input

    @Override
    protected void layoutContent(int cx, int cy, int cw, int ch) {
        int lw = Math.min(170, cw / 3);
        types.setBounds(cx, cy, lw, ch - 22);
        fields.setBounds(cx + lw + 6, cy, cw - lw - 6, ch - 56);
        preview.setBounds(cx + lw + 6, cy + ch - 40, cw - lw - 6, 16);
        cancel.setBounds(cx + cw - 172, cy + ch - 16, 54, 16);
        ok.setBounds(cx + cw - 114, cy + ch - 16, 114, 16);
    }

    @Override
    protected void renderContent(PoseStack p, int mx, int my, float pt) {
        types.render(p, mx, my, pt);
        fields.render(p, mx, my, pt);
        text(p, "Result:", preview.x, preview.y - 10, Theme.MUTED);
        preview.render(p, mx, my, pt);
        ok.render(p, mx, my, pt);
        cancel.render(p, mx, my, pt);
    }

    @Override
    protected boolean clickContentHasField(double mx, double my) { return fields.contains(mx, my) || preview.contains(mx, my); }

    @Override
    protected boolean clickContent(double mx, double my, int button) {
        if (ok.mouseClicked(mx, my, button) || cancel.mouseClicked(mx, my, button)) return true;
        if (preview.mouseClicked(mx, my, button)) return true;
        if (types.mouseClicked(mx, my, button)) { if (Focus.get() != null) Focus.clear(); return true; }
        return fields.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return types.mouseScrolled(mx, my, delta) || fields.mouseScrolled(mx, my, delta);
    }
}
