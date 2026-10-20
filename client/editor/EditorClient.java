package com.luminacity.advancedinteractions.client.editor;

import com.luminacity.advancedinteractions.client.HologramPicker;
import com.luminacity.advancedinteractions.client.editor.ui.Theme;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.editor.C2S_EditorRequest;
import com.luminacity.advancedinteractions.network.editor.EditorNet;
import com.luminacity.advancedinteractions.network.editor.S2C_EditorData;
import com.luminacity.advancedinteractions.network.editor.S2C_EditorList;
import com.luminacity.advancedinteractions.network.editor.S2C_EditorReply;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client entry points for the editor: packet handlers and the open-editor key. */
public final class EditorClient {
    private EditorClient() {}

    private static EditorSession active;

    public static void onData(S2C_EditorData p) {
        Minecraft mc = Minecraft.getInstance();
        HologramDef def;
        try {
            def = HologramDef.load(EditorNet.decompress(p.def()));
        } catch (Exception e) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Could not open the editor: " + e.getMessage())
                    .withStyle(ChatFormatting.RED), false);
            return;
        }
        if (active != null) active.endPreview();
        EditorSession s = new EditorSession(def, p.revision(), EditorContext.from(p.context()));
        active = s;
        // Open first: closing a previous editor clears its preview, which may be this same hologram.
        mc.setScreen(new EditorScreen(s));
        s.preview();
    }

    public static void onList(S2C_EditorList p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BrowserScreen b) b.load(p.list());
        else mc.setScreen(new BrowserScreen(p.list()));
    }

    public static void onReply(S2C_EditorReply p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof EditorScreen es) { es.onReply(p.kind(), p.ok(), p.message(), p.payload()); return; }
        if (mc.screen instanceof BrowserScreen bs) { bs.toast(p.message(), p.ok() ? Theme.OK : Theme.INVALID); return; }
        if (mc.player != null && !p.message().isEmpty()) {
            mc.player.displayClientMessage(Component.literal(p.message()).withStyle(p.ok() ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        }
    }

    static void ended(EditorSession s) { if (active == s) active = null; }

    // ---------------------------------------------------------------- picking a block to link

    private static EditorSession pickSession;
    private static String pickName;
    private static long pickUntil;

    /** Step out of the editor until the player right-clicks the block to link (left-click or 30s cancels). */
    static void pickBlock(EditorSession s, String name) {
        pickSession = s;
        pickName = name;
        pickUntil = System.currentTimeMillis() + 30_000;
        Minecraft.getInstance().setScreen(null);
    }

    public static boolean picking() { return pickSession != null; }

    /** A mouse click while picking: right = link the targeted block, left = cancel. Always consumes the click. */
    public static void onPickClick(boolean left) {
        Minecraft mc = Minecraft.getInstance();
        EditorSession s = pickSession;
        if (s == null || mc.level == null) return;
        if (left) { backToEditor("Linking cancelled.", Theme.WARN); return; }
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh)
                || mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            mc.player.displayClientMessage(Component.literal("Aim at a block, then right-click.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        var pos = bh.getBlockPos().immutable();
        var dim = mc.level.dimension();
        String name = pickName;
        s.structural(() -> {
            s.def.links.removeIf(l -> l.name().equalsIgnoreCase(name));
            s.def.links.add(new HologramDef.BlockLink(name, dim, pos));
        });
        String what = mc.level.getBlockState(pos).getBlock().getName().getString();
        backToEditor("Linked '" + name + "' to the " + what + ". Save to keep it.", Theme.OK);
    }

    /** Hint on screen while picking, and give up after 30 seconds. */
    public static void tickPick() {
        if (pickSession == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.currentTimeMillis() > pickUntil) { backToEditor("Linking timed out.", Theme.WARN); return; }
        if (mc.player != null && mc.screen == null) {
            mc.player.displayClientMessage(Component.literal("Right-click the block to link as '" + pickName + "'  (left-click cancels)")
                    .withStyle(ChatFormatting.AQUA), true);
        }
    }

    private static void backToEditor(String toast, int color) {
        EditorSession s = pickSession;
        pickSession = null;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        EditorScreen screen = new EditorScreen(s);
        active = s;
        mc.setScreen(screen);
        s.preview();
        screen.toast(toast, color);
    }

    /**
     * The editor key: edit the hologram under the crosshair, or open the browser (staff). The server
     * decides who may edit what (staff, or a player-owned hologram's owner).
     */
    public static void onEditorKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        var hit = HologramPicker.pickAny(mc);
        // Players may edit holograms they own (the server checks); only staff get the hologram list.
        if (!mc.player.hasPermissions(2) && hit == null) return;
        NetworkHandler.CHANNEL.sendToServer(hit != null
                ? C2S_EditorRequest.of("open", hit)
                : C2S_EditorRequest.of("list", null));
    }
}
