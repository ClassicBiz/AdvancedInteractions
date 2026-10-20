package com.luminacity.advancedinteractions.api.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;

/**
 * A buy or sell from a hologram. Built on the server from the clicked element's stored action
 * ({@code advholo:trade?side=buy&qty={input.qty}&shop=ID&...}); the client only reported the click.
 *
 * @param quantity already checked to be a whole number from 1 to 10 000
 * @param params   every parameter of the trade action (item, price, anything a shop mod wants)
 * @param linkDim  dimension of the hologram's linked block named by "link=" (or its first link), may be null
 * @param linkPos  that block, may be null
 */
public record TradeRequest(ServerPlayer player, UUID hologramId, String elementId, Side side, int quantity,
                           Map<String, String> params, ResourceKey<Level> linkDim, BlockPos linkPos) {
    public enum Side { BUY, SELL }

    public String param(String key, String def) {
        String v = params.get(key);
        return v == null || v.isBlank() ? def : v;
    }
}
