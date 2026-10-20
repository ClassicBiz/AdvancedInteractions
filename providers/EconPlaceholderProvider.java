package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Economy;
import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {econ.*}: shows money from whichever economy mod is connected through the Economy API (any mod
 * that registers, or a built-in connector such as Lightman's Currency). AdvancedInteractions holds no money
 * itself. Registered as a fallback: an economy mod that registers its own {econ.*} (EconomyCore) replaces it.
 */
public final class EconPlaceholderProvider implements Placeholders.PlaceholderProvider {

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        Economy.Provider eco = Economy.get();
        Economy.AsyncProvider async = Economy.async();
        if (eco == null && async == null) return "";
        switch (key == null ? "" : key.toLowerCase(java.util.Locale.ROOT)) {
            case "player.balance", "balance" -> {
                if (ctx == null || !(ctx.viewer() instanceof ServerPlayer p)) return "";
                if (eco != null) return eco.format(eco.balance(p));
                double est = async.balanceEstimate(p);
                return est < 0 ? "" : async.format(est);
            }
            case "format" -> {
                double amount;
                try { amount = Double.parseDouble(args == null ? "" : args.trim().replace(",", "")); } catch (NumberFormatException e) { return ""; }
                return eco != null ? eco.format(amount) : async.format(amount);
            }
            default -> { return ""; }
        }
    }

    @Override
    public Map<String, String> keys() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("player.balance", "The viewer's balance in the connected economy mod");
        m.put("format:<amount>", "An amount written the economy mod's way, e.g. {econ.format:25}");
        return m;
    }
}
