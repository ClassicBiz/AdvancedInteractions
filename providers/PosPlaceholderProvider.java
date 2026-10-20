package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.world.entity.player.Player;

/** Built-in {pos.*} placeholder provider (viewer position). */
public final class PosPlaceholderProvider implements Placeholders.PlaceholderProvider {

    @Override public boolean isClientSide(String key) { return true; }

    @Override public java.util.Map<String, String> keys() {
        return java.util.Map.of("x", "Viewer X (also y, z)", "blockx", "Viewer block X (also blocky, blockz)");
    }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null) return "";
        Player p = ctx.viewer();
        if (p == null) return "";

        key = key == null ? "" : key.toLowerCase();

        return switch (key) {
            case "x" -> Double.toString(p.position().x);
            case "y" -> Double.toString(p.position().y);
            case "z" -> Double.toString(p.position().z);
            case "blockx" -> Integer.toString(p.blockPosition().getX());
            case "blocky" -> Integer.toString(p.blockPosition().getY());
            case "blockz" -> Integer.toString(p.blockPosition().getZ());
            default -> "";
        };
    }
}
