package dev.lucistarlink.light.runtime;

import net.minecraft.world.level.block.state.BlockState;

public record BlockChangeRecord(int x, int y, int z, BlockState oldState, BlockState newState) {
}
