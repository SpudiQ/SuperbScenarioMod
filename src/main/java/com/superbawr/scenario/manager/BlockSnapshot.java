package com.superbawr.scenario.manager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/**
 * Stores original block state before it was modified during a round.
 */
public class BlockSnapshot {
    private final BlockPos pos;
    private final BlockState originalState;
    private final CompoundTag blockEntityData; // nullable, for chests/signs etc.

    public BlockSnapshot(BlockPos pos, BlockState originalState, CompoundTag blockEntityData) {
        this.pos = pos;
        this.originalState = originalState;
        this.blockEntityData = blockEntityData;
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockState getOriginalState() {
        return originalState;
    }

    public CompoundTag getBlockEntityData() {
        return blockEntityData;
    }
}
