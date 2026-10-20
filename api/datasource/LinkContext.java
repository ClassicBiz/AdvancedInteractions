package com.luminacity.advancedinteractions.api.datasource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * A linked block being read: where it is, the link's name, the hologram and the viewer (may be null
 * for values shared by everyone).
 */
public record LinkContext(ServerLevel level, BlockPos pos, String linkName, UUID hologramId, ServerPlayer viewer) {
    public BlockState state() { return level.getBlockState(pos); }
    public BlockEntity blockEntity() { return level.getBlockEntity(pos); }
}
