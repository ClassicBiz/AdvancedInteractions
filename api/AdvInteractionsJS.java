package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import com.luminacity.advancedinteractions.state.PlayerState;
import com.luminacity.advancedinteractions.util.ConditionEval;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * Scripting entry point (KubeJS and other script engines). Every method takes plain functions, so a
 * script registers placeholders, actions and conditions without Java. No KubeJS dependency: scripts
 * load this class directly.
 *
 * <pre>
 * // kubejs/server_scripts/holograms.js
 * const AI = Java.loadClass('com.luminacity.advancedinteractions.api.AdvInteractionsJS')
 *
 * // {myserver.motd}
 * AI.placeholder('myserver', 'motd', 'Message of the day', (args, player, holo) => 'Welcome!')
 *
 * // Button action "myserver:heal"
 * AI.action('myserver:heal', (player, params, holo) => { player.heal(20) })
 *
 * // Condition "isnight()" for "Show only if"
 * AI.condition('isnight', (arg, player, holo) => player.level.isNight())
 *
 * // Player variables shared with {state.KEY} and advholo:state_* actions
 * AI.setState(player, 'coins', '10')
 * </pre>
 */
public final class AdvInteractionsJS {
    private AdvInteractionsJS() {}

    @FunctionalInterface
    public interface PlaceholderFn { Object get(String args, ServerPlayer player, String hologram); }

    @FunctionalInterface
    public interface NamespaceFn { Object get(String key, String args, ServerPlayer player, String hologram); }

    @FunctionalInterface
    public interface ActionFn { void run(ServerPlayer player, Map<String, String> params, String hologram); }

    @FunctionalInterface
    public interface ConditionFn { boolean test(String arg, ServerPlayer player, String hologram); }

    private static String alias(Placeholders.PHContext ctx) {
        return ctx == null || ctx.holo() == null ? "" : ctx.holo().alias;
    }

    private static ServerPlayer player(Placeholders.PHContext ctx) {
        return ctx != null && ctx.viewer() instanceof ServerPlayer sp ? sp : null;
    }

    /** {namespace.key} (or {namespace.key:args}) resolved by the function; null/undefined shows as empty. */
    public static void placeholder(String namespace, String key, String description, PlaceholderFn fn) {
        Placeholders.registerKey(namespace, key, description, (args, ctx) -> {
            Object v = fn.get(args, player(ctx), alias(ctx));
            return v == null ? "" : String.valueOf(v);
        });
    }

    /** A whole namespace handled by one function: fn(key, args, player, hologram). */
    public static void namespace(String namespace, NamespaceFn fn) {
        Placeholders.register(namespace, (key, args, ctx) -> {
            Object v = fn.get(key, args, player(ctx), alias(ctx));
            return v == null ? "" : String.valueOf(v);
        });
    }

    /** A button action id ("myserver:heal"); parameters come from "myserver:heal?amount=5". */
    public static void action(String id, ActionFn fn) {
        ActionRegistry.register(id, (player, view, params) -> {
            Map<String, String> p = new HashMap<>();
            if (params != null) {
                for (String pair : com.luminacity.advancedinteractions.util.QueryParams.split(params)) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) p.put(pair.substring(0, eq), pair.substring(eq + 1));
                    else if (!pair.isBlank()) p.put(pair, "");
                }
            }
            String holo = "";
            if (view != null) {
                var loc = com.luminacity.advancedinteractions.data.HologramSavedData.findAnywhere(player.getServer(), view.id());
                if (loc != null) holo = loc.def().alias;
            }
            fn.run(player, p, holo);
        });
    }

    /** A visibility condition usable as name(arg) in "Show only if". */
    public static void condition(String name, ConditionFn fn) {
        ConditionEval.register(name, (arg, ctx) -> {
            ServerPlayer p = player(ctx);
            return p != null && fn.test(arg, p, alias(ctx));
        });
    }

    public static String getState(ServerPlayer player, String key) {
        String v = PlayerState.get(player.getServer()).get(player.getUUID(), key);
        return v == null ? "" : v;
    }

    /** Set a player variable ({state.KEY}); the player's holograms refresh shortly. Null clears it. */
    public static void setState(ServerPlayer player, String key, String value) {
        PlayerState.get(player.getServer()).set(player.getUUID(), key, value);
    }

    /** Push fresh hologram content to a player now (after changing what they should see). */
    public static void refresh(ServerPlayer player) {
        C2S_RequestSync.serverPushForPlayer(player);
    }
}
