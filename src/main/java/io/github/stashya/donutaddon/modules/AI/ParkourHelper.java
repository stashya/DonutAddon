package io.github.stashya.donutaddon.modules.AI;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class ParkourHelper {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Jump states
    private boolean isJumping = false;
    private int jumpCooldown = 0;
    private BlockPos jumpTarget = null;
    private Vec3d jumpStartPos = null;

    // Constants
    private static final int JUMP_COOLDOWN_TICKS = 10;
    private static final double JUMP_DISTANCE_THRESHOLD = 1.5;

    public static class JumpCheck {
        public final boolean canJump;
        public final boolean shouldJump;
        public final BlockPos targetPos;
        public final String reason;

        public JumpCheck(boolean canJump, boolean shouldJump, BlockPos targetPos, String reason) {
            this.canJump = canJump;
            this.shouldJump = shouldJump;
            this.targetPos = targetPos;
            this.reason = reason;
        }
    }

    public JumpCheck checkJumpOpportunity(PlayerEntity player, Direction movingDirection) {
        if (jumpCooldown > 0 || !player.isOnGround() || isJumping) {
            return new JumpCheck(false, false, null, "On cooldown or already jumping");
        }

        World world = mc.world;
        BlockPos playerPos = player.getBlockPos();

        // Check for 1-block drop first
        BlockPos frontPos = playerPos.offset(movingDirection);
        BlockPos frontGround = frontPos.down();
        BlockState frontState = world.getBlockState(frontPos);
        BlockState frontGroundState = world.getBlockState(frontGround);

        // If there's a 1-block drop ahead, just walk off it
        if (frontState.isAir() && !frontGroundState.isAir() && frontGroundState.isSolidBlock(world, frontGround)) {
            // Check it's not deeper than 1 block
            BlockPos twoDown = frontGround.down();
            if (world.getBlockState(twoDown).isAir()) {
                return new JumpCheck(true, false, null, "Safe 1-block drop - just walk");
            }
        }

        // If there's no obstacle at foot level, no need to jump
        if (frontState.isAir() || !frontState.isSolidBlock(world, frontPos)) {
            return new JumpCheck(true, false, null, "No obstacle");
        }

        // Check if it's a single block we can jump over
        BlockPos aboveFront = frontPos.up();
        BlockState aboveFrontState = world.getBlockState(aboveFront);

        // Check 2 blocks above the obstacle
        BlockPos twoAboveFront = frontPos.up(2);
        BlockState twoAboveFrontState = world.getBlockState(twoAboveFront);

        // Can't jump if there's no clearance above the obstacle
        if (!aboveFrontState.isAir() || !twoAboveFrontState.isAir()) {
            return new JumpCheck(false, false, null, "No clearance above obstacle");
        }

        // Check if there's a safe landing spot
        BlockPos landingPos = frontPos.offset(movingDirection);
        BlockState landingState = world.getBlockState(landingPos);
        BlockPos landingGround = landingPos.down();
        BlockState landingGroundState = world.getBlockState(landingGround);

        // Check if landing spot is clear and has ground (or is a safe 1-block drop)
        boolean safeLanding = landingState.isAir() &&
            world.getBlockState(landingPos.up()).isAir();

        // Check for ground at landing spot or 1 block below
        boolean hasLandingGround = landingGroundState.isSolidBlock(world, landingGround);
        if (!hasLandingGround) {
            // Check for 1-block drop at landing
            BlockPos landingTwoDown = landingGround.down();
            hasLandingGround = world.getBlockState(landingTwoDown).isSolidBlock(world, landingTwoDown);
        }

        if (safeLanding && hasLandingGround) {
            return new JumpCheck(true, true, landingPos, "Safe jump available (may include drop)");
        }

        // Check for a simple 1-block jump (landing on the obstacle itself)
        if (aboveFrontState.isAir() && world.getBlockState(aboveFront.up()).isAir()) {
            return new JumpCheck(true, true, aboveFront, "Jump onto block");
        }

        return new JumpCheck(false, false, null, "No safe landing");
    }

    public boolean startJump(BlockPos target) {
        if (isJumping || jumpCooldown > 0) {
            return false;
        }

        PlayerEntity player = mc.player;
        if (player == null || !player.isOnGround()) {
            return false;
        }

        jumpTarget = target;
        jumpStartPos = player.getPos();
        isJumping = true;

        // Initiate jump
        player.jump();

        return true;
    }

    public void update() {
        // Update cooldown
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }

        // Update jump state
        if (isJumping && mc.player != null) {
            Vec3d currentPos = mc.player.getPos();

            // Check if we've moved far enough or if we're on ground again
            if (mc.player.isOnGround() && currentPos.distanceTo(jumpStartPos) > 0.5) {
                // Jump completed
                completeJump();
            } else if (currentPos.distanceTo(jumpStartPos) > JUMP_DISTANCE_THRESHOLD) {
                // We've traveled far enough
                completeJump();
            }
        }
    }

    private void completeJump() {
        isJumping = false;
        jumpCooldown = JUMP_COOLDOWN_TICKS;
        jumpTarget = null;
        jumpStartPos = null;
    }

    public boolean isJumping() {
        return isJumping;
    }

    public void reset() {
        isJumping = false;
        jumpCooldown = 0;
        jumpTarget = null;
        jumpStartPos = null;
    }
}
