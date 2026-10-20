package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.ClickSettings;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.util.ConditionEval;
import com.luminacity.advancedinteractions.util.PermUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-viewer element rules on the server: who may use a clickable element, whether it's greyed out,
 * and per-player show/hide overrides set by advholo:show / advholo:hide actions.
 */
public final class WidgetRules {
    private WidgetRules() {}

    /** May this player use (click) the element? Visibility is checked separately. */
    public static boolean canUse(ServerPlayer p, HologramDef def, PanelWidget w, Level lvl) {
        ClickSettings c = w.interaction();
        if (c == null || !c.hasUseRule()) return true;
        if (p == null) return false;
        boolean perm = c.usePerm.isBlank() && c.useOp < 0 || PermUtil.check(p, c.usePerm.isBlank() ? null : c.usePerm, c.useOp);
        return perm && ConditionEval.test(c.useCondition, def, p, lvl);
    }

    /** Shown greyed out and refuses clicks (disabled condition, or not allowed and set to "show disabled"). */
    public static boolean isDisabled(ServerPlayer p, HologramDef def, PanelWidget w, Level lvl) {
        ClickSettings c = w.interaction();
        if (c == null) return false;
        if (!c.disabledIf.isBlank() && ConditionEval.test(c.disabledIf, def, p, lvl)) return true;
        return c.unusable == ClickSettings.Unusable.DISABLE && !canUse(p, def, w, lvl);
    }

    /** Hidden because the player may not use it and it's set to hide. */
    public static boolean hiddenAsUnusable(ServerPlayer p, HologramDef def, PanelWidget w, Level lvl) {
        ClickSettings c = w.interaction();
        return c != null && c.hasUseRule() && c.unusable == ClickSettings.Unusable.HIDE
                && ClickSettings.isClickable(w) && !canUse(p, def, w, lvl);
    }

    // ---------------------------------------------------------------- show / hide per player

    private record Override(boolean visible, long untilMs) {}
    /** player -> "holoId|widgetId" -> override */
    private static final Map<UUID, Map<String, Override>> OVERRIDES = new ConcurrentHashMap<>();

    /** Show or hide an element for one player; seconds > 0 reverts it afterwards. */
    public static void setVisible(ServerPlayer p, HologramDef def, String widgetId, boolean visible, double seconds) {
        long until = seconds > 0 ? System.currentTimeMillis() + (long) (seconds * 1000) : Long.MAX_VALUE;
        OVERRIDES.computeIfAbsent(p.getUUID(), k -> new ConcurrentHashMap<>()).put(def.id + "|" + widgetId, new Override(visible, until));
    }

    /** Is the element visible for this player, given its "start hidden" flag and any override? */
    public static boolean visibleByToggle(ServerPlayer p, HologramDef def, PanelWidget w) {
        Map<String, Override> m = p == null ? null : OVERRIDES.get(p.getUUID());
        Override o = m == null ? null : m.get(def.id + "|" + w.id());
        if (o != null) {
            if (System.currentTimeMillis() <= o.untilMs()) return o.visible();
            m.remove(def.id + "|" + w.id());
        }
        return !w.startHidden();
    }

    /** True while some timed show/hide is pending for this player (so the sync re-checks soon). */
    public static boolean hasTimedOverrides(UUID player) {
        Map<String, Override> m = OVERRIDES.get(player);
        if (m == null) return false;
        for (Override o : m.values()) if (o.untilMs() != Long.MAX_VALUE) return true;
        return false;
    }

    public static void clearPlayer(UUID player) { OVERRIDES.remove(player); }

    // ---------------------------------------------------------------- click distance

    /** Effective click distance for a hologram (its own setting, capped by the server config). */
    public static double clickRange(HologramDef def) {
        double r = def.clickRange > 0 ? def.clickRange : com.luminacity.advancedinteractions.config.AIConfig.clickReach();
        return Math.min(r, com.luminacity.advancedinteractions.config.AIConfig.clickReachMax());
    }
}
