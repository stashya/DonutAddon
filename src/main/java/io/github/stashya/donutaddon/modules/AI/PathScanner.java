package io.github.stashya.donutaddon.modules.AI;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

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
        DANGEROUS_BLOCK,
        SANDWICH_TRAP  // NEW: The sandwich trap pattern
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
        int expectedYLevel = 0;

        // FIRST PRIORITY: Check for sandwich traps early
        SandwichTrapResult trapResult = checkForSandwichTraps(start, direction, depth);
        if (trapResult.found) {
            System.out.println("SANDWICH TRAP detected at distance " + trapResult.distance);
            detectedHazards.add(trapResult.hazardPos);
            return new ScanResult(false, HazardType.SANDWICH_TRAP, trapResult.distance, detectedHazards);
        }

        // Continue with normal scanning...
        for (int forward = 1; forward <= depth; forward++) {
            BlockPos checkCenter = offsetPosition(start, direction, forward, 0, expectedYLevel - 1);

            // Scan for fluids with wider area
            for (int sideways = -scanWidthFluids; sideways <= scanWidthFluids; sideways++) {
                for (int vertical = -1; vertical <= height; vertical++) {
                    // For fluids below player level (vertical < 0), only check the direct path

                    if (vertical < 0 && sideways != 0) {
                        continue;
                    }

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


                    boolean checkFalling = (sideways == 0 && vertical == 3);

                    HazardType hazard = checkBlock(world, checkPos, vertical, strictGround, forward, sideways, checkFalling);
                    if (hazard != HazardType.NONE) {
                        // Check for special case of safe drops
                        if (hazard == HazardType.UNSAFE_GROUND && forward == 1 && vertical == -1) {

                            boolean foundGround = false;
                            for (int i = 0; i <= 2; i++) {
                                if (canWalkOn(world, checkPos.down(i), world.getBlockState(checkPos.down(i)))) {
                                    foundGround = true;
                                    break;
                                }
                            }
                            if (foundGround) {
                                continue;
                            }
                        }

                        detectedHazards.add(checkPos.toImmutable());
                        return new ScanResult(false, hazard, forward, detectedHazards);
                    }
                }
            }
        }

        return new ScanResult(true, HazardType.NONE, -1, detectedHazards);
    }

    private static class SandwichTrapResult {
        final boolean found;
        final int distance;
        final BlockPos hazardPos;

        SandwichTrapResult(boolean found, int distance, BlockPos hazardPos) {
            this.found = found;
            this.distance = distance;
            this.hazardPos = hazardPos;
        }
    }

    private SandwichTrapResult checkForSandwichTraps(BlockPos start, Direction direction, int maxDepth) {
        World world = mc.world;

        // Check for Pattern 1: Current position sandwich trap
        // (ceiling above us + block at foot level ahead + air at head level ahead)
        BlockPos ceilingPos = start.up(2);
        BlockPos footAhead = start.offset(direction).down(); // Ground level ahead
        BlockPos headAhead = start.offset(direction); // Head level ahead

        if (!world.getBlockState(ceilingPos).isAir() &&        // Ceiling above us
            !world.getBlockState(footAhead).isAir() &&         // Block at foot level ahead
            world.getBlockState(headAhead).isAir()) {          // Air at head level ahead

            System.out.println("DEBUG: Pattern 1 Sandwich Trap at current position!");
            System.out.println("  Ceiling at: " + ceilingPos);
            System.out.println("  Foot block at: " + footAhead);
            System.out.println("  Air gap at: " + headAhead);
            return new SandwichTrapResult(true, 1, footAhead);
        }

        // Check for Pattern 2: Offset sandwich trap ahead
        // (we're under a ceiling, and ahead has foot block but no head block)
        for (int distance = 1; distance <= Math.min(maxDepth, 3); distance++) {
            // Check if we currently have a ceiling (or will at distance-1)
            BlockPos prevCeiling = start.offset(direction, distance - 1).up(2);
            boolean hasCeilingBehind = !world.getBlockState(prevCeiling).isAir();

            // Check the position ahead
            BlockPos checkBase = start.offset(direction, distance).down(); // Ground level
            BlockPos footPos = checkBase.up(); // Foot level (Y+0 from ground)
            BlockPos headPos = footPos.up(); // Head level (Y+1 from ground)

            // Pattern 2: Ceiling behind/above + foot block ahead + air at head
            if (hasCeilingBehind &&
                !world.getBlockState(footPos).isAir() &&  // Block at foot level
                world.getBlockState(headPos).isAir()) {    // Air at head level

                System.out.println("DEBUG: Pattern 2 Sandwich Trap at distance " + distance);
                System.out.println("  Previous ceiling at: " + prevCeiling);
                System.out.println("  Foot block at: " + footPos);
                System.out.println("  Air gap at: " + headPos);
                return new SandwichTrapResult(true, distance, footPos);
            }

            // Also check if there's a direct sandwich at this distance
            BlockPos ceilingAhead = start.offset(direction, distance).up(2);
            BlockPos nextFootPos = start.offset(direction, distance + 1).down();
            BlockPos nextHeadPos = start.offset(direction, distance + 1);

            if (!world.getBlockState(ceilingAhead).isAir() &&
                !world.getBlockState(nextFootPos).isAir() &&
                world.getBlockState(nextHeadPos).isAir()) {

                System.out.println("DEBUG: Forward Sandwich Trap at distance " + (distance + 1));
                return new SandwichTrapResult(true, distance + 1, nextFootPos);
            }
        }

        return new SandwichTrapResult(false, -1, null);
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
            System.out.println("=== GRAVEL DETECTED ===");
            System.out.println("Block position: " + pos);
            System.out.println("Block type: " + block);
            System.out.println("Forward distance: " + forwardDist);
            System.out.println("Vertical offset: " + yOffset);
            int playerHeadBlockY = mc.player.getBlockPos().getY() + 1;
            int blocksAboveHeadBlock = pos.getY() - playerHeadBlockY;

            System.out.println("Player head block Y: " + playerHeadBlockY);
            System.out.println("Blocks above head block: " + blocksAboveHeadBlock);
            System.out.println("====================");

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

        BlockPos frontGround = playerPos.offset(direction).down();
        BlockState frontGroundState = world.getBlockState(frontGround);

        BlockPos frontFoot = playerPos.offset(direction);
        BlockState frontFootState = world.getBlockState(frontFoot);

        if (frontFootState.isAir() && !frontGroundState.isAir()) {
            BlockPos twoBelow = frontGround.down();
            return world.getBlockState(twoBelow).isAir();
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
            block == Blocks.DAMAGED_ANVIL;
    }

    private boolean isDangerousBlock(Block block) {
        return block == Blocks.TNT ||
            block == Blocks.FIRE ||
            block == Blocks.SOUL_FIRE ||
            block == Blocks.MAGMA_BLOCK ||
            block == Blocks.WITHER_ROSE ||
            block == Blocks.SWEET_BERRY_BUSH ||
            block == Blocks.POWDER_SNOW ||
            block == Blocks.CACTUS ||
            block == Blocks.INFESTED_DEEPSLATE ||
            block == Blocks.SCULK ||
            block == Blocks.SMALL_AMETHYST_BUD ||
            block == Blocks.MEDIUM_AMETHYST_BUD ||
            block == Blocks.LARGE_AMETHYST_BUD ||
            block == Blocks.AMETHYST_CLUSTER ||
            block == Blocks.INFESTED_STONE;
    }

    private BlockPos offsetPosition(BlockPos start, Direction forward, int forwardDist, int sidewaysDist, int verticalDist) {
        Direction left = forward.rotateYCounterclockwise();

        return start
            .offset(forward, forwardDist)
            .offset(left, sidewaysDist)
            .offset(Direction.UP, verticalDist);
    }
}
