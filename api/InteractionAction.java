package com.luminacity.advancedinteractions.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * Simple string-keyed action registry.
 * Other mods can register actions at runtime to respond to hologram button clicks.
 */
public interface InteractionAction {
    void execute(ServerPlayer player, IHologramView view, String params);
}