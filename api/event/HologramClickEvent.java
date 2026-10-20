package com.luminacity.advancedinteractions.api.event;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Posted on {@code MinecraftForge.EVENT_BUS} (server side) whenever a player clicks a hologram element,
 * after the click passed the built-in checks (distance, visibility, permissions, disabled state, cooldown)
 * and before its action runs.
 *
 * <p>Cancel it to stop the action; {@link #setMessage} tells the player why. Listeners may also change
 * the action ({@link #setActionId}). Only the server decides what runs: the client only reports which
 * element was clicked.</p>
 */
@Cancelable
public class HologramClickEvent extends Event {
    public enum ClickType { PRIMARY, LEFT, SHIFT }

    private final ServerPlayer player;
    private final UUID hologramId;
    private final String hologramName;
    private final String elementId;
    private final ClickType clickType;
    private final ResourceKey<Level> linkedDim;
    private final BlockPos linkedBlock;
    private final Map<String, String> variables;
    private String actionId;
    private String message = "";

    public HologramClickEvent(ServerPlayer player, UUID hologramId, String hologramName, String elementId, ClickType clickType,
                              String actionId, ResourceKey<Level> linkedDim, BlockPos linkedBlock, Map<String, String> variables) {
        this.player = player;
        this.hologramId = hologramId;
        this.hologramName = hologramName;
        this.elementId = elementId;
        this.clickType = clickType;
        this.actionId = actionId == null ? "" : actionId;
        this.linkedDim = linkedDim;
        this.linkedBlock = linkedBlock;
        this.variables = variables == null ? Map.of() : Collections.unmodifiableMap(variables);
    }

    public ServerPlayer getPlayer() { return player; }
    public UUID getHologramId() { return hologramId; }
    /** The hologram's name (alias). */
    public String getHologramName() { return hologramName; }
    /** The clicked element's id, or "__panel__" for a click on the panel itself. */
    public String getElementId() { return elementId; }
    public ClickType getClickType() { return clickType; }
    /** The action that will run (after this event). */
    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId == null ? "" : actionId; }
    /** The hologram's first linked block, or null. */
    public BlockPos getLinkedBlock() { return linkedBlock; }
    public ResourceKey<Level> getLinkedDimension() { return linkedDim; }
    /** The player's input values and variables visible to this hologram (read-only). */
    public Map<String, String> getVariables() { return variables; }
    /** Shown to the player when the event is cancelled. */
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }
}
