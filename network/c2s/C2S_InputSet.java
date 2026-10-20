package com.luminacity.advancedinteractions.network.c2s;

import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.interaction.ActionLog;
import com.luminacity.advancedinteractions.interaction.ActionRunner;
import com.luminacity.advancedinteractions.interaction.InputValues;
import com.luminacity.advancedinteractions.interaction.WidgetRules;
import com.luminacity.advancedinteractions.panel.InputWidget;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> server: the player entered a value in an input field. The server checks the field is
 * visible to them and in reach, validates the value (type, min/max, filter), then stores it.
 */
public record C2S_InputSet(UUID holo, String widgetId, String value) {

    public static void encode(C2S_InputSet p, FriendlyByteBuf buf) {
        buf.writeUUID(p.holo);
        buf.writeUtf(p.widgetId, 64);
        buf.writeUtf(p.value, 256);
    }

    public static C2S_InputSet decode(FriendlyByteBuf buf) {
        return new C2S_InputSet(buf.readUUID(), buf.readUtf(64), buf.readUtf(256));
    }

    public static void handle(C2S_InputSet p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sp = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sp == null) return;
            ServerLevel lvl = sp.getLevel();
            var loc = HologramSavedData.findAnywhere(lvl.getServer(), p.holo);
            if (loc == null) return;
            HologramDef def = loc.def();
            HologramDef snap = C2S_RequestSync.prepareForViewer(def, sp, lvl);
            if (snap == null || (snap.dim != null && !snap.dim.equals(lvl.dimension()))) return;
            double reach = WidgetRules.clickRange(def) + C2S_HologramClick.panelHalfExtent(snap) + 1.5;
            if (sp.getEyePosition().distanceTo(new Vec3(snap.x, snap.y, snap.z)) > reach) return;

            InputWidget field = null;
            for (PanelWidget w : snap.widgets) if (w instanceof InputWidget iw && iw.id().equals(p.widgetId)) field = iw;
            if (field == null) return;
            if (!WidgetRules.canUse(sp, def, field, lvl) || WidgetRules.isDisabled(sp, def, field, lvl)) {
                sp.displayClientMessage(Component.literal("That's not available right now.").withStyle(ChatFormatting.RED), true);
                return;
            }
            String err = InputValues.set(sp, def, field, p.value);
            if (err != null) {
                sp.displayClientMessage(Component.literal(err).withStyle(ChatFormatting.RED), true);
                return;
            }
            String onChange = field.interaction().action;
            if (onChange != null && !onChange.isBlank()) {
                boolean ok = ActionRunner.run(sp, lvl, def, snap, onChange);
                ActionLog.log(sp, def, field.id(), onChange, ok ? "ok" : "failed");
            }
            C2S_RequestSync.syncOne(sp, def);
        });
        ctx.get().setPacketHandled(true);
    }
}
