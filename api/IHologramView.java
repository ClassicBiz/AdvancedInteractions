package com.luminacity.advancedinteractions.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import java.util.UUID;

public interface IHologramView {
    UUID id();
    ResourceKey<Level> dimension();
    double x();
    double y();
    double z();
    BlockPos anchorBlock(); // May be null
}