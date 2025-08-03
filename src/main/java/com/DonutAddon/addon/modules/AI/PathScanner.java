package com.DonutAddon.addon.modules.AI;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.block.*;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PathScanner {
    private final IBaritone baritone;
    private final IPlayerContext ctx;

    // Scan width settings
    private int scanWidthFallingBlocks = 2;
    private int scanWidthFluids = 4;

    public PathScanner() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.ctx = baritone.getPlayerContext();
    }

    public void updateScanWidths(int fallingBlocks, int fluids) {
        this.scanWidthFallingBlocks = fallingBlocks;
        this.scanWidthFluids = fluids;
    }

    public static class ScanResult {
        private final boolean safe;
        private final HazardType hazardType;
        private final int hazardDistance;

        public ScanResult(boolean safe, HazardType hazardType, int hazardDistance) {
            this.safe = safe;
            this.hazardType = hazardType;
            this.hazardDistance = hazardDistance;
        }

        public boolean isSafe() { return safe; }
        public HazardType getHazardType() { return hazardType; }
        public int getHazardDistance() { return hazardDistance; }
    }

    public enum HazardType {
        NONE,
        LAVA,
        WATER,
        FALLING_BLOCK,
        UNSAFE_GROUND,
        DANGEROUS_BLOCK
    }

    public ScanResult scanDirection(BlockPos start, Direction direction, int depth, int height, boolean strictGround) {
        World world = ctx.world();

        // Scan with different widths based on hazard type
        for (int forward = 1; forward <= depth; forward++) {
            // First pass - check for fluids with wider scan
            for (int sideways = -scanWidthFluids; sideways <= scanWidthFluids; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, vertical);
                    BlockState state = world.getBlockState(checkPos);

                    // Check for fluids
                    HazardType fluidHazard = checkForFluids(state);
                    if (fluidHazard != HazardType.NONE) {
                        return new ScanResult(false, fluidHazard, forward);
                    }
                }
            }

            // Second pass - check other hazards with appropriate width
            int scanWidth = Math.max(scanWidthFallingBlocks, scanWidthFluids);
            for (int sideways = -scanWidth; sideways <= scanWidth; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, vertical);

                    // Only check falling blocks within their scan width
                    boolean checkFalling = Math.abs(sideways) <= scanWidthFallingBlocks;

                    HazardType hazard = checkBlock(world, checkPos, vertical, strictGround, forward, sideways, checkFalling);
                    if (hazard != HazardType.NONE) {
                        return new ScanResult(false, hazard, forward);
                    }
                }
            }
        }

        return new ScanResult(true, HazardType.NONE, -1);
    }

    private HazardType checkForFluids(BlockState state) {
        Block block = state.getBlock();

        if (block == Blocks.LAVA || state.getFluidState().getFluid() == Fluids.LAVA ||
            state.getFluidState().getFluid() == Fluids.FLOWING_LAVA) {
            return HazardType.LAVA;
        }

        if (block == Blocks.WATER || state.getFluidState().getFluid() == Fluids.WATER ||
            state.getFluidState().getFluid() == Fluids.FLOWING_WATER) {
            return HazardType.WATER;
        }

        return HazardType.NONE;
    }

    private HazardType checkBlock(World world, BlockPos pos, int yOffset, boolean strictGround,
                                  int forwardDist, int sidewaysDist, boolean checkFalling) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // Ground level checks
        if (yOffset == -1) {
            // Must be solid ground in the walking path (center 3 blocks)
            if (Math.abs(sidewaysDist) <= 1 && !canWalkOn(world, pos, state)) {
                return HazardType.UNSAFE_GROUND;
            }
        }

        // Check for falling blocks only within their scan width
        if (checkFalling && yOffset >= 0 && isFallingBlock(block)) {
            return HazardType.FALLING_BLOCK;
        }

        // Check for dangerous blocks
        if (isDangerousBlock(block)) {
            return HazardType.DANGEROUS_BLOCK;
        }

        return HazardType.NONE;
    }

    private boolean canWalkOn(World world, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (!state.isSolidBlock(world, pos)) return false;

        Block block = state.getBlock();
        if (block == Blocks.MAGMA_BLOCK || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE) {
            return false;
        }

        return state.isFullCube(world, pos) ||
            block instanceof SlabBlock ||
            block instanceof StairsBlock;
    }

    private boolean isFallingBlock(Block block) {
        return block == Blocks.SAND ||
            block == Blocks.RED_SAND ||
            block == Blocks.GRAVEL ||
            block == Blocks.ANVIL ||
            block == Blocks.CHIPPED_ANVIL ||
            block == Blocks.DAMAGED_ANVIL ||
            block == Blocks.POINTED_DRIPSTONE ||
            block == Blocks.DRIPSTONE_BLOCK;
    }

    private boolean isDangerousBlock(Block block) {
        return block == Blocks.TNT ||
            block == Blocks.FIRE ||
            block == Blocks.SOUL_FIRE ||
            block == Blocks.MAGMA_BLOCK ||
            block == Blocks.WITHER_ROSE ||
            block == Blocks.SWEET_BERRY_BUSH ||
            block == Blocks.POINTED_DRIPSTONE ||
            block == Blocks.POWDER_SNOW ||
            block == Blocks.CACTUS;
    }

    private BlockPos offsetPosition(BlockPos start, Direction forward, int forwardDist, int sidewaysDist, int verticalDist) {
        Direction left = forward.rotateYCounterclockwise();

        return start
            .offset(forward, forwardDist)
            .offset(left, sidewaysDist)
            .offset(Direction.UP, verticalDist);
    }
}
