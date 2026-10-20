
package com.luminacity.advancedinteractions.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientEvents {

    private static final boolean DEBUG_HITS = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        com.luminacity.advancedinteractions.client.editor.EditorClient.tickPick();

        // Our keys are handled in onKey; drain their click counters so they don't pile up.
        while (ClientKeybinds.EDITOR.consumeClick()) { }
        while (ClientKeybinds.INTERACT.consumeClick()) { }
    }

    /**
     * Interact (default R) and editor (default H) keys. Read from the raw key event instead of
     * consumeClick(): when another mod binds the same key, Forge gives the click count to only one of
     * the conflicting bindings, so ours would silently never fire.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key evt) {
        if (evt.getAction() != GLFW.GLFW_PRESS) return; // not on key repeat
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return; // never while typing in chat or a GUI
        var key = InputConstants.getKey(evt.getKey(), evt.getScanCode());
        if (ClientKeybinds.INTERACT.isActiveAndMatches(key)) {
            boolean hit = HologramPicker.activate(mc);
            if (DEBUG_HITS) mc.player.displayClientMessage(Component.literal(hit ? "interact: hit" : "interact: no hologram hit"), true);
        }
        if (ClientKeybinds.EDITOR.isActiveAndMatches(key)) {
            com.luminacity.advancedinteractions.client.editor.EditorClient.onEditorKey();
        }
    }

    /** Server images belong to the server we left. */
    /** Hover tooltip of a clickable hologram element, under the crosshair. */
    @SubscribeEvent
    public static void onOverlay(net.minecraftforge.client.event.RenderGuiOverlayEvent.Post evt) {
        if (evt.getOverlay() != net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CROSSHAIR.type()) return;
        String raw = HologramPicker.hoveredTooltip();
        var holo = HologramPicker.hoveredTooltipHolo();
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (raw == null || holo == null || mc.player == null || mc.level == null || mc.options.hideGui) return;

        var d = new com.luminacity.advancedinteractions.hologram.HologramDef();
        d.id = holo.id();
        if (holo.meta() != null) d.meta.putAll(holo.meta());
        String text = com.luminacity.advancedinteractions.api.Placeholders.apply(raw,
                new com.luminacity.advancedinteractions.api.Placeholders.PHContext(mc.level, mc.player, d));
        String[] raw2 = text.replace("\\n", "\n").split("\n");
        var lines = new net.minecraft.network.chat.Component[raw2.length];
        for (int i = 0; i < raw2.length; i++) lines[i] = com.luminacity.advancedinteractions.util.TextFmt.component(raw2[i]);

        var font = mc.font;
        int w = 0;
        for (var l : lines) w = Math.max(w, font.width(l));
        int h = lines.length * (font.lineHeight + 1) - 1;
        int x = (evt.getWindow().getGuiScaledWidth() - w) / 2;
        int y = evt.getWindow().getGuiScaledHeight() / 2 + 12;
        var pose = evt.getPoseStack();
        net.minecraft.client.gui.GuiComponent.fill(pose, x - 4, y - 3, x + w + 4, y + h + 3, 0xD0100010);
        net.minecraft.client.gui.GuiComponent.fill(pose, x - 4, y - 3, x + w + 4, y - 2, 0xFF5000A0);
        for (int i = 0; i < lines.length; i++) {
            font.drawShadow(pose, lines[i], x + (w - font.width(lines[i])) / 2f, y + i * (font.lineHeight + 1), 0xFFFFFFFF);
        }
    }

    /** Mouse wheel while looking at a slider or number field on a panel: change it instead of the hotbar slot. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent evt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        HologramPicker.Hit hit = HologramPicker.pick(mc);
        if (hit == null || hit.button() == null || hit.button().disabled()) return;
        var w = HologramPicker.widgetFor(hit.holo(), hit.button().widgetId());
        if (w instanceof com.luminacity.advancedinteractions.panel.InputWidget in
                && InputClient.scroll(mc, hit.holo(), in, evt.getScrollDelta())) {
            evt.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut evt) {
        ClientImages.clear();
    }

    // Pre only: it's the cancellable variant and fires once per press (the abstract parent also receives Post).
    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Pre evt) {
        if (evt.getAction() != GLFW.GLFW_PRESS) return;
        boolean left = evt.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (!left && evt.getButton() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Never steal clicks from an open GUI (inventory, chat, menus).
        if (mc.screen != null) return;

        // Picking a block to link from the editor: this click picks (right) or cancels (left).
        if (com.luminacity.advancedinteractions.client.editor.EditorClient.picking()) {
            com.luminacity.advancedinteractions.client.editor.EditorClient.onPickClick(left);
            evt.setCanceled(true);
            return;
        }

        // Left-click is only taken by buttons that have a left-click action; otherwise it still breaks/attacks.
        boolean hit = left
                ? HologramPicker.activate(mc, com.luminacity.advancedinteractions.panel.ClickSettings.Type.LEFT)
                : HologramPicker.activate(mc);
        if (hit) {
            if (DEBUG_HITS) mc.player.displayClientMessage(Component.literal("clicked hologram"), true);
            evt.setCanceled(true);
        } else {
            if (DEBUG_HITS) mc.player.displayClientMessage(Component.literal("no hologram hit"), true);
        }
    }
}
