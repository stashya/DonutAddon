package com.DonutAddon.addon.modules.AI;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.*;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.block.Block;

import java.util.HashSet;
import java.util.Set;

public class PathScanner {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Scan width settings
    private int scanWidthFallingBlocks = 2;
    private int scanWidthFluids = 4;

    // Tunnel dimensions
    private int tunnelWidth = 3;
    private int tunnelHeight = 3;

    public PathScanner() {
        // Empty constructor - no Baritone needed
    }

    public void updateScanWidths(int fallingBlocks, int fluids) {
        this.scanWidthFallingBlocks = fallingBlocks;
        this.scanWidthFluids = fluids;
    }

    public void updateTunnelDimensions(int width, int height) {
        this.tunnelWidth = width;
        this.tunnelHeight = height;
    }

    public static class ScanResult {
        private final boolean safe;
        private final HazardType hazardType;
        private final int hazardDistance;
        private final Set<BlockPos> hazardPositions;

        public ScanResult(boolean safe, HazardType hazardType, int hazardDistance) {
            this(safe, hazardType, hazardDistance, new HashSet<>());
        }

        public ScanResult(boolean safe, HazardType hazardType, int hazardDistance, Set<BlockPos> hazardPositions) {
            this.safe = safe;
            this.hazardType = hazardType;
            this.hazardDistance = hazardDistance;
            this.hazardPositions = hazardPositions;
        }

        public boolean isSafe() { return safe; }
        public HazardType getHazardType() { return hazardType; }
        public int getHazardDistance() { return hazardDistance; }
        public Set<BlockPos> getHazardPositions() { return hazardPositions; }
    }

    public enum HazardType {
        NONE,
        LAVA,
        WATER,
        FALLING_BLOCK,
        UNSAFE_GROUND,
        DANGEROUS_BLOCK
    }

    private boolean isWalkablePath(World world, BlockPos groundPos, int forwardDist) {
        BlockState groundState = world.getBlockState(groundPos);

        // Direct ground is solid - good to walk
        if (canWalkOn(world, groundPos, groundState)) {
            return true;
        }

        // If air at expected ground level, check for 1-block drop
        if (groundState.isAir()) {
            // Check 1 block down
            BlockPos oneDown = groundPos.down();
            BlockState oneDownState = world.getBlockState(oneDown);

            if (canWalkOn(world, oneDown, oneDownState)) {
                // Safe 1-block drop
                return true;
            }

            // For the very first block (forwardDist == 1), also accept if 2 blocks down is solid
            // This handles the edge case where we're looking at the air space of a drop
            if (forwardDist == 1) {
                BlockPos twoDown = groundPos.down(2);
                BlockState twoDownState = world.getBlockState(twoDown);
                if (canWalkOn(world, twoDown, twoDownState)) {
                    return true;
                }
            }
        }

        return false;
    }

    public ScanResult scanDirection(BlockPos start, Direction direction, int depth, int height, boolean strictGround) {
        World world = mc.world;
        Set<BlockPos> detectedHazards = new HashSet<>();

        BlockPos currentGroundLevel = start.down();
        // Track the expected Y level as we scan (for handling drops)
        int expectedYLevel = 0;

        // Scan ahead
        for (int forward = 1; forward <= depth; forward++) {
            // First, check if there's a drop at this position
            BlockPos checkCenter = offsetPosition(start, direction, forward, 0, expectedYLevel - 1);

            // Adjust expected Y level if we detect a drop
            if (forward > 1 && !world.getBlockState(checkCenter).isAir()) {
                // Ground is higher than expected, adjust
                expectedYLevel++;
            } else if (world.getBlockState(checkCenter).isAir() &&
                canWalkOn(world, checkCenter.down(), world.getBlockState(checkCenter.down()))) {
                // This is a drop, adjust expected level
                expectedYLevel--;
            }

            // Scan for fluids with wider area
            for (int sideways = -scanWidthFluids; sideways <= scanWidthFluids; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, expectedYLevel + vertical);
                    BlockState state = world.getBlockState(checkPos);

                    // Check for fluids
                    HazardType fluidHazard = checkForFluids(state);
                    if (fluidHazard != HazardType.NONE) {
                        detectedHazards.add(checkPos.toImmutable());
                        return new ScanResult(false, fluidHazard, forward, detectedHazards);
                    }
                }
            }

            // Check other hazards
            for (int sideways = -scanWidthFallingBlocks; sideways <= scanWidthFallingBlocks; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    BlockPos checkPos = offsetPosition(start, direction, forward, sideways, expectedYLevel + vertical);

                    // Only check falling blocks above the tunnel
                    boolean checkFalling = (sideways == 0 && vertical >= tunnelHeight);

                    HazardType hazard = checkBlock(world, checkPos, vertical, strictGround, forward, sideways, checkFalling);
                    if (hazard != HazardType.NONE) {
                        // Check for special case of safe drops
                        if (hazard == HazardType.UNSAFE_GROUND && forward == 1 && vertical == -1) {
                            // Double-check if it's really unsafe or just a drop
                            boolean foundGround = false;
                            for (int i = 0; i <= 2; i++) {
                                if (canWalkOn(world, checkPos.down(i), world.getBlockState(checkPos.down(i)))) {
                                    foundGround = true;
                                    break;
                                }
                            }
                            if (foundGround) {
                                continue; // It's actually a safe drop, continue scanning
                            }
                        }

                        detectedHazards.add(checkPos.toImmutable()); // ADD THE HAZARD POSITION
                        return new ScanResult(false, hazard, forward, detectedHazards); // INCLUDE HAZARDS
                    }
                }
            }
        }

        return new ScanResult(true, HazardType.NONE, -1, detectedHazards); // Include empty set for safe result
    }

    private boolean shouldCheckForFallingBlocks(int sideways, int vertical) {
        // Only check for falling blocks if:
        // 1. Directly in player's path (sideways = 0)
        // 2. ABOVE tunnel height (could fall on player)
        return sideways == 0 && vertical >= tunnelHeight;
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
            // Only check the center block for walkability (1-block wide path)
            if (sidewaysDist == 0) {
                if (strictGround) {
                    // Original strict checking - must be solid ground
                    if (!canWalkOn(world, pos, state)) {
                        return HazardType.UNSAFE_GROUND;
                    }
                } else {
                    // Lenient checking - allow drops
                    if (!canWalkOn(world, pos, state)) {
                        // It's not solid ground, but check if it's a safe drop
                        boolean safeDropFound = false;

                        // Check up to 2 blocks down for solid ground
                        for (int i = 1; i <= 2; i++) {
                            BlockPos belowPos = pos.down(i);
                            BlockState belowState = world.getBlockState(belowPos);

                            if (canWalkOn(world, belowPos, belowState)) {
                                safeDropFound = true;
                                break;
                            }
                        }

                        // Only report unsafe if we found no ground within 2 blocks
                        if (!safeDropFound) {
                            return HazardType.UNSAFE_GROUND;
                        }
                    }
                }
            }
            // Don't check side blocks at all - we only need 1-block wide path
        }

        // Check for falling blocks only if checkFalling is true
        if (checkFalling && isFallingBlock(block)) {
            return HazardType.FALLING_BLOCK;
        }

        // Check for dangerous blocks
        if (isDangerousBlock(block)) {
            return HazardType.DANGEROUS_BLOCK;
        }

        return HazardType.NONE;
    }

    public boolean hasDropAhead(BlockPos playerPos, Direction direction) {
        World world = mc.world;

        // Check the ground 1 block ahead
        BlockPos frontGround = playerPos.offset(direction).down();
        BlockState frontGroundState = world.getBlockState(frontGround);

        // Check if the block at foot level ahead is air
        BlockPos frontFoot = playerPos.offset(direction);
        BlockState frontFootState = world.getBlockState(frontFoot);

        // It's a drop if front foot is air but there's ground 1 block below
        if (frontFootState.isAir() && !frontGroundState.isAir()) {
            // Check if it's exactly a 1-block drop
            BlockPos twoBelow = frontGround.down();
            return world.getBlockState(twoBelow).isAir(); // Make sure it's not a deeper drop
        }

        return false;
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
