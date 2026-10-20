package com.luminacity.advancedinteractions.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who may put player holograms where. Land/claim mods (e.g. LuminEstates, LuminaGuard) register a
 * provider; without any, players may place their own holograms anywhere (and only the creator can
 * edit them).
 */
public final class Ownership {
    private Ownership() {}

    public interface Provider {
        /**
         * May this player put (or move, or link) a hologram at this position?
         * @return true/false, or null for "not my land, no opinion"
         */
        Boolean mayPlace(ServerPlayer player, ServerLevel level, BlockPos pos);

        /**
         * May this player edit a hologram owned by someone else at this position (e.g. a co-owner of
         * the property)? Return null for no opinion.
         */
        default Boolean mayEditOthers(ServerPlayer player, ServerLevel level, BlockPos pos) { return null; }
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    public static void register(Provider p) { if (p != null) PROVIDERS.add(p); }

    /** null when allowed, otherwise the reason. Any provider saying no wins. */
    public static String placeProblem(ServerPlayer player, ServerLevel level, BlockPos pos) {
        for (Provider p : PROVIDERS) {
            try {
                if (Boolean.FALSE.equals(p.mayPlace(player, level, pos))) return "You can't place holograms here.";
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static boolean mayEditOthers(ServerPlayer player, ServerLevel level, BlockPos pos) {
        boolean yes = false;
        for (Provider p : PROVIDERS) {
            try {
                Boolean b = p.mayEditOthers(player, level, pos);
                if (Boolean.FALSE.equals(b)) return false;
                if (Boolean.TRUE.equals(b)) yes = true;
            } catch (Exception ignored) { }
        }
        return yes;
    }
}
