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
        BlockPos footAhead = start.offset(direction);        // Same Y as player feet
        BlockPos headAhead = start.offset(direction).up();   // Same Y as player head (Y+1)

        if (!world.getBlockState(ceilingPos).isAir() &&        // Ceiling above us
            !world.getBlockState(footAhead).isAir() &&         // Block at foot level ahead
            world.getBlockState(headAhead).isAir()) {          // Air at head level ahead

            System.out.println("DEBUG: Pattern 1 Sandwich Trap at current position!");
            System.out.println("  Ceiling at: " + ceilingPos);
            System.out.println("  Foot block at: " + footAhead);
            System.out.println("  Air gap at: " + headAhead);
            return new SandwichTrapResult(true, 1, footAhead);
        }

        // Check for Pattern 2: Direct sandwich trap ahead
        // (block at foot level ahead + air at head level ahead + ceiling above the air)
        BlockPos footAheadP2 = start.offset(direction);          // Same Y as player feet
        BlockPos headAheadP2 = start.offset(direction).up();     // Y+1 (head level)
        BlockPos ceilingAheadP2 = start.offset(direction).up(2); // Ceiling level ahead

        if (!world.getBlockState(footAheadP2).isAir() &&       // Block at foot level ahead
            world.getBlockState(headAheadP2).isAir() &&        // Air at head level ahead
            !world.getBlockState(ceilingAheadP2).isAir()) {    // Ceiling above the air ahead

            System.out.println("DEBUG: Pattern 2 Sandwich Trap directly ahead!");
            System.out.println("  Foot block at: " + footAheadP2);
            System.out.println("  Air gap at: " + headAheadP2);
            System.out.println("  Ceiling at: " + ceilingAheadP2);
            return new SandwichTrapResult(true, 1, footAheadP2);
        }

        // Check for both sandwich trap patterns up to 8 blocks ahead
        for (int distance = 2; distance <= Math.min(maxDepth, 8); distance++) {
            BlockPos checkPos = start.offset(direction, distance);
            BlockPos p1Ceiling = checkPos.up(2);                     // Y+2 - ceiling above position
            BlockPos p1FootAhead = checkPos.offset(direction);       // Y+0 - foot level ahead
            BlockPos p1HeadAhead = checkPos.offset(direction).up();

            if (!world.getBlockState(p1Ceiling).isAir() &&
                !world.getBlockState(p1FootAhead).isAir() &&
                world.getBlockState(p1HeadAhead).isAir()) {

                System.out.println("DEBUG: Forward Pattern 1 Sandwich Trap at distance " + distance);
                System.out.println("  Position ceiling at: " + p1Ceiling);
                System.out.println("  Foot block ahead at: " + p1FootAhead);
                System.out.println("  Air gap ahead at: " + p1HeadAhead);
                return new SandwichTrapResult(true, distance + 1, p1FootAhead);
            }

            // Pattern 2 check at this distance:
            // (block at foot + air at head + ceiling above)
            BlockPos p2Foot = checkPos;         // Y+0 - foot level at check position
            BlockPos p2Head = checkPos.up();    // Y+1 - head level at check position
            BlockPos p2Ceiling = checkPos.up(2); // Y+2 - ceiling above check position

            if (!world.getBlockState(p2Foot).isAir() &&
                world.getBlockState(p2Head).isAir() &&
                !world.getBlockState(p2Ceiling).isAir()) {

                System.out.println("DEBUG: Forward Pattern 2 Sandwich Trap at distance " + distance);
                System.out.println("  Foot block at: " + p2Foot);
                System.out.println("  Air gap at: " + p2Head);
                System.out.println("  Ceiling at: " + p2Ceiling);
                return new SandwichTrapResult(true, distance, p2Foot);
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
        }

        // Rest of the method remains the same...
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
