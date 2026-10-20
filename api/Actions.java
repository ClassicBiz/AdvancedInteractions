
package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Actions {

    public record ActionCtx(ServerPlayer player, ServerLevel level, HologramDef holo) {}

    public interface ActionHandler {
        void handle(String id, Map<String,String> params, ActionCtx ctx);
    }

    private static final Map<String, ActionHandler> REGISTRY = new ConcurrentHashMap<>();

    public static void registerPrefix(String prefix, ActionHandler h) {
        if (prefix == null || prefix.isEmpty() || h == null) return;
        REGISTRY.put(prefix.toLowerCase(), h);
    }

    public static boolean route(String actionId, ServerPlayer player, ServerLevel level, HologramDef holo) {
        if (actionId == null || actionId.isEmpty()) return false;
        int idx = actionId.indexOf(':');
        if (idx <= 0) return false;
        String prefix = actionId.substring(0, idx).toLowerCase();
        String rest = actionId.substring(idx+1);
        String id = rest;
        Map<String,String> params = Collections.emptyMap();
        int q = rest.indexOf('?');
        if (q >= 0) {
            id = rest.substring(0, q);
            String qs = rest.substring(q+1);
            params = parseQuery(qs);
        }
        ActionHandler h = REGISTRY.get(prefix);
        if (h == null) return false;
        h.handle(id, params, new ActionCtx(player, level, holo));
        return true;
    }

    /**
     * Single entry point for every hologram action:
     * <ol>
     *   <li>Prefix handlers ({@link #registerPrefix}, e.g. "advholo:page_next").</li>
     *   <li>Named actions ({@link ActionRegistry#register}, params from "id?params").</li>
     *   <li>Anything else runs as a command: as the clicking player ("cmd:/time set day"), or as the
     *       server console ("console:/give {player.name} diamond") when {@code allowConsole} is set.</li>
     * </ol>
     */
    public static boolean dispatch(String actionId, ServerPlayer player, ServerLevel level, HologramDef holo,
                                   IHologramView view, String params) {
        return dispatch(actionId, player, level, holo, view, params, false);
    }

    public static boolean dispatch(String actionId, ServerPlayer player, ServerLevel level, HologramDef holo,
                                   IHologramView view, String params, boolean allowConsole) {
        if (actionId == null || actionId.isBlank() || player == null) return false;
        if (route(actionId, player, level, holo)) return true;
        return ActionRegistry.execute(actionId, player, view, params == null ? "" : params, allowConsole);
    }

    private static final ThreadLocal<Boolean> FAILED = ThreadLocal.withInitial(() -> false);

    /**
     * Call from inside an action handler when the action didn't go through (e.g. a refused trade), so
     * click costs are refunded and "if it failed" chain steps run.
     */
    public static void reportFailure() { FAILED.set(true); }

    /** Read and clear the failure flag (used by the action runner). */
    public static boolean takeFailure() {
        boolean f = FAILED.get();
        FAILED.set(false);
        return f;
    }

    /** Registered prefixes (for command suggestions). */
    public static java.util.Set<String> prefixes() {
        return REGISTRY.keySet();
    }

    private static Map<String,String> parseQuery(String qs) {
        Map<String,String> m = new HashMap<>();
        for (String pair : com.luminacity.advancedinteractions.util.QueryParams.split(qs)) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) { m.put(pair, ""); continue; }
            m.put(pair.substring(0,eq), pair.substring(eq+1));
        }
        return m;
    }
}
