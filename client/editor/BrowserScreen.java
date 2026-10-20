package com.luminacity.advancedinteractions.client.editor;

import com.luminacity.advancedinteractions.client.editor.ui.Field;
import com.luminacity.advancedinteractions.client.editor.ui.Focus;
import com.luminacity.advancedinteractions.client.editor.ui.Form;
import com.luminacity.advancedinteractions.client.editor.ui.MiniButton;
import com.luminacity.advancedinteractions.client.editor.ui.Popup;
import com.luminacity.advancedinteractions.client.editor.ui.PromptPopup;
import com.luminacity.advancedinteractions.client.editor.ui.Rows;
import com.luminacity.advancedinteractions.client.editor.ui.Theme;
import com.luminacity.advancedinteractions.client.editor.ui.Ui;
import com.luminacity.advancedinteractions.client.editor.ui.UiHost;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Lists every hologram (nearest first) to edit, teleport to, or create a new one. */
public class BrowserScreen extends Screen implements UiHost {
    private record Entry(UUID id, String alias, String dim, double x, double y, double z, double dist,
                         boolean attached, int widgets, int pages) {}

    private final List<Entry> all = new ArrayList<>();
    private final Form list = new Form();
    private final List<Popup> popups = new ArrayList<>();
    private String query = "";
    private final Field search = new Field(64, () -> query, s -> { query = s; rebuild(); }).placeholder("Search by name...");
    private final MiniButton create = new MiniButton("+ New hologram", this::create).style(MiniButton.Style.ACCENT);
    private final MiniButton refresh = new MiniButton("Refresh", () -> send("list", null, ""));
    private final MiniButton demo = new MiniButton("Demo", () -> send("spawndemo", null, ""))
            .tooltip("Spawn the feature showcase hologram in front of you");
    private final MiniButton close = new MiniButton("Close", this::onClose);
    private String tooltip, toast = "";
    private int toastColor;
    private long toastUntil;

    public BrowserScreen(CompoundTag data) {
        super(Component.literal("Holograms"));
        load(data);
    }

    public void load(CompoundTag data) {
        all.clear();
        ListTag l = data.getList("holos", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            all.add(new Entry(t.getUUID("id"), t.getString("alias"), t.getString("dim"), t.getDouble("x"), t.getDouble("y"),
                    t.getDouble("z"), t.getDouble("dist"), t.getBoolean("attached"), t.getInt("widgets"), t.getInt("pages")));
        }
        // Nearest in this dimension first, then other dimensions by name.
        all.sort(Comparator.comparingDouble((Entry e) -> e.dist() < 0 ? Double.MAX_VALUE : e.dist())
                .thenComparing(e -> e.alias().toLowerCase(Locale.ROOT)));
        if (width > 0) rebuild();
    }

    @Override public void openPopup(Popup p) { popups.add(p); p.layoutIn(width, height); }
    @Override public void closePopup(Popup p) { popups.remove(p); }
    @Override public void tooltip(String text) { tooltip = text; }
    @Override public void toast(String m, int c) { toast = m; toastColor = c; toastUntil = System.currentTimeMillis() + 5000; }

    @Override
    protected void init() {
        UiHost.setCurrent(this);
        int w = Math.min(520, width - 20), x = (width - w) / 2;
        search.setBounds(x, 26, w - 244, 16); // ends 4px before the Demo button (it used to cover it and take its clicks)
        demo.setBounds(x + w - 240, 26, 40, 16);
        create.setBounds(x + w - 196, 26, 100, 16);
        refresh.setBounds(x + w - 92, 26, 52, 16);
        close.setBounds(x + w - 36, 26, 36, 16);
        list.setBounds(x, 46, w, height - 62);
        for (Popup p : popups) p.layoutIn(width, height);
        rebuild();
    }

    private void rebuild() {
        list.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Entry e : all) {
            if (!q.isEmpty() && !e.alias().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown++;
            String where = e.dist() >= 0 ? Math.round(e.dist()) + "m away" : e.dim().replace("minecraft:", "");
            String label = e.alias() + "   §7" + where + " - " + e.widgets() + " widgets, " + e.pages() + " page(s)"
                    + (e.attached() ? " - follows a player" : "");
            list.add(Rows.item(() -> label, null, () -> send("open", e.id(), ""),
                    new MiniButton("Edit", () -> send("open", e.id(), "")).style(MiniButton.Style.ACCENT),
                    new MiniButton("Go to", () -> send("teleport", e.id(), "")).tooltip("Teleport to it")));
        }
        if (shown == 0) list.add(Rows.hint(all.isEmpty() ? "No holograms yet. Create one!" : "No holograms match."));
    }

    private void create() {
        UiHost.open(PromptPopup.single("New hologram", "Name (letters, numbers, _ - . +)", "", n -> send("create", null, n)));
    }

    private void send(String op, UUID id, String a) {
        NetworkHandler.CHANNEL.sendToServer(new C2S_EditorRequest(op, id, a, "", false));
    }

    @Override
    public void render(PoseStack p, int mx, int my, float pt) {
        tooltip = null;
        boolean popup = !popups.isEmpty();
        int imx = popup ? -10000 : mx, imy = popup ? -10000 : my;
        Ui.rect(p, 0, 0, width, height, Theme.BACKDROP);
        Ui.textCentered(p, "Holograms (" + all.size() + ")", width / 2, 10, Theme.TEXT);
        search.render(p, imx, imy, pt);
        demo.render(p, imx, imy, pt);
        create.render(p, imx, imy, pt);
        refresh.render(p, imx, imy, pt);
        close.render(p, imx, imy, pt);
        list.render(p, imx, imy, pt);
        if (System.currentTimeMillis() < toastUntil) Ui.textCentered(p, toast, width / 2, height - 12, toastColor);
        p.pushPose();
        p.translate(0, 0, 400);
        for (Popup pop : popups) pop.render(p, mx, my, pt);
        p.popPose();
        if (tooltip != null) renderTooltip(p, Component.literal(tooltip), mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (!popups.isEmpty()) return popups.get(popups.size() - 1).mouseClicked(mx, my, b);
        if (search.mouseClicked(mx, my, b)) return true;
        if (Focus.get() != null) Focus.clear();
        return demo.mouseClicked(mx, my, b) || create.mouseClicked(mx, my, b) || refresh.mouseClicked(mx, my, b) || close.mouseClicked(mx, my, b)
                || list.mouseClicked(mx, my, b);
    }

    @Override public boolean mouseScrolled(double mx, double my, double d) {
        if (!popups.isEmpty()) return popups.get(popups.size() - 1).mouseScrolled(mx, my, d);
        return list.mouseScrolled(mx, my, d);
    }

    @Override public boolean mouseDragged(double mx, double my, int b, double dx, double dy) { return list.mouseDragged(mx, my, b, dx, dy); }
    @Override public boolean mouseReleased(double mx, double my, int b) { return list.mouseReleased(mx, my, b); }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (!popups.isEmpty()) {
            Popup top = popups.get(popups.size() - 1);
            if (key == GLFW.GLFW_KEY_ESCAPE) { top.close(); return true; }
            if (Focus.get() != null && key != GLFW.GLFW_KEY_ENTER && key != GLFW.GLFW_KEY_KP_ENTER) return Focus.keyPressed(key, scan, mods);
            return top.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && Focus.get() == null) { onClose(); return true; }
        if (Focus.keyPressed(key, scan, mods)) return true;
        return super.keyPressed(key, scan, mods);
    }

    @Override public boolean charTyped(char c, int mods) { return Focus.charTyped(c, mods); }
    @Override public void tick() { Focus.tick(); }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        if (Focus.get() != null) Focus.clear();
        if (UiHost.current() == this) UiHost.setCurrent(null);
    }
}
