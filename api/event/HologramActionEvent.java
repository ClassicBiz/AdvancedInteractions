package com.luminacity.advancedinteractions.api.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Posted on {@code MinecraftForge.EVENT_BUS} (server side) when a hologram runs
 * {@code advholo:event?name=NAME&...}, so other mods and scripts can react to named events.
 */
public class HologramActionEvent extends Event {
    private final ServerPlayer player;
    private final UUID hologramId;
    private final String name;
    private final Map<String, String> params;

    public HologramActionEvent(ServerPlayer player, UUID hologramId, String name, Map<String, String> params) {
        this.player = player;
        this.hologramId = hologramId;
        this.name = name;
        this.params = params == null ? Map.of() : Collections.unmodifiableMap(params);
    }

    public ServerPlayer getPlayer() { return player; }
    /** May be null when the action didn't come from a hologram. */
    public UUID getHologramId() { return hologramId; }
    public String getName() { return name; }
    /** All parameters of the action (including "name"). */
    public Map<String, String> getParams() { return params; }
}
