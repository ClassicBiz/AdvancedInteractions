package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.state.Vars;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.TreeMap;

/**
 * Server event flags for schedules: hologram elements can show only while a flag is on
 * ({@code flag(sale)} in a condition). Set from other mods ({@code Flags.set("sale", true)}),
 * commands ({@code /aholo flag on sale}) or actions ({@code advholo:flag?name=sale&on=true}).
 * Saved with the world; stored as global variables "flag.NAME" ({@code {var.global.flag.sale}}).
 */
public final class Flags {
    private Flags() {}

    private static MinecraftServer server() { return ServerLifecycleHooks.getCurrentServer(); }

    public static boolean get(String name) {
        MinecraftServer s = server();
        return s != null && "true".equalsIgnoreCase(Vars.global(s, "flag." + name));
    }

    public static void set(String name, boolean on) {
        MinecraftServer s = server();
        if (s == null || name == null || !name.trim().matches("[A-Za-z0-9_\\-.]{1,48}")) return;
        Vars.set(s, null, "flag." + name.trim(), on ? "true" : null, false);
    }

    /** Flags that are on. */
    public static Map<String, Boolean> all() {
        Map<String, Boolean> out = new TreeMap<>();
        MinecraftServer s = server();
        if (s == null) return out;
        Vars.globals(s).forEach((k, v) -> { if (k.startsWith("flag.") && "true".equalsIgnoreCase(v)) out.put(k.substring(5), true); });
        return out;
    }
}
