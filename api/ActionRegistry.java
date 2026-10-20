package com.luminacity.advancedinteractions.api;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** global action registry. */
public final class ActionRegistry {
    private static final Map<String, InteractionAction> REGISTRY = new ConcurrentHashMap<>();

    private ActionRegistry() {}

    public static void register(String id, InteractionAction action) {
        REGISTRY.put(id, action);
    }

    /** Runs as the server console. Only level-4 ops (or holders of {@link #CONSOLE_ACTION_PERM}) may set these. */
    public static final String CONSOLE_PREFIX = "console:";
    /** LuckPerms node that lets a non-level-4 builder set console actions. */
    public static final String CONSOLE_ACTION_PERM = "advancedinteractions.action.console";

    /**
     * Placeholders a console action may contain. Anything else could carry text a player or a
     * lower-level builder controls (meta, data bindings, display names) into a full-permission command.
     */
    private static final Set<String> CONSOLE_SAFE_TOKENS = Set.of(
            "player.name", "player.uuid",
            "player.x", "player.y", "player.z", "player.blockx", "player.blocky", "player.blockz",
            "pos.x", "pos.y", "pos.z", "pos.blockx", "pos.blocky", "pos.blockz",
            "holo.id", "holo.alias", "holo.x", "holo.y", "holo.z", "holo.dim",
            "world.id", "world.name");

    /** True if the stored (unresolved) action runs as console. */
    public static boolean isConsoleAction(String action) {
        return action != null && action.trim().toLowerCase(Locale.ROOT).startsWith(CONSOLE_PREFIX);
    }

    /** @return the first placeholder a console action may not use, or null if all are allowed. */
    public static String unsafeConsoleToken(String action) {
        for (String canon : Placeholders.extractCanonicalTokens(action)) {
            if (!CONSOLE_SAFE_TOKENS.contains(canon.toLowerCase(Locale.ROOT))) return canon;
        }
        return null;
    }

    public static boolean execute(String id, ServerPlayer player, IHologramView view, String params) {
        return execute(id, player, view, params, false);
    }

    /**
     * @param allowConsole true only when the stored, unresolved action itself starts with "console:".
     *                     A placeholder that resolves to "console:..." never runs as console.
     */
    public static boolean execute(String id, ServerPlayer player, IHologramView view, String params, boolean allowConsole) {
        InteractionAction a = id == null ? null : REGISTRY.get(id);
        // "demo:door_toggle?x=1&y=64&z=2" -> action "demo:door_toggle", params "x=1&y=64&z=2"
        if (a == null && id != null) {
            int q = id.indexOf('?');
            if (q > 0) {
                InteractionAction base = REGISTRY.get(id.substring(0, q));
                if (base != null) {
                    String query = id.substring(q + 1);
                    String merged = (params == null || params.isEmpty()) ? query : (query + "&" + params);
                    base.execute(player, view, merged);
                    return true;
                }
            }
        }
        if (a == null) {
            // Fallback: treat unknown action IDs as commands.
            // This keeps button wiring dead-simple: actionId can be "time set day" or "/time set day".
            //   cmd:/player:  run as the clicking player, with the player's own permissions (default)
            //   console:      run as the server console, positioned at the clicking player
            if (id == null || id.isEmpty()) return false;

            String cmd = id;
            String low = cmd.toLowerCase(Locale.ROOT);
            boolean console = false;
            if (low.startsWith(CONSOLE_PREFIX)) {
                if (!allowConsole) return false;
                console = true;
                cmd = cmd.substring(CONSOLE_PREFIX.length());
            } else if (low.startsWith("cmd:")) {
                cmd = cmd.substring(4);
            } else if (low.startsWith("player:")) {
                cmd = cmd.substring(7);
            }
            cmd = cmd.trim();

            // Convenience: allow underscores-as-spaces for command arguments.
            // e.g. cmd:time_set_day  -> "time set day"
            if (cmd.indexOf(' ') < 0 && cmd.indexOf('_') >= 0) {
                cmd = cmd.replace('_', ' ');
            }

            if (cmd.startsWith("/")) cmd = cmd.substring(1);

            try {
                MinecraftServer server = player.getServer();
                if (server == null) return false;
                CommandSourceStack source = console
                        ? server.createCommandSourceStack().withLevel(player.getLevel()).withPosition(player.position())
                        : player.createCommandSourceStack();
                server.getCommands().performPrefixedCommand(source, cmd);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        a.execute(player, view, params);
        return true;
    }

    public static boolean has(String id) { return REGISTRY.containsKey(id); }


    public static java.util.Set<String> ids() {
        return REGISTRY.keySet();
    }
}