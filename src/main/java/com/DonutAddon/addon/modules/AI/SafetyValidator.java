package com.DonutAddon.addon.modules.AI;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluids;

public class SafetyValidator {
    private Vec3d lastPosition;
    private int stuckTicks = 0;
    private int jumpCooldown = 0;
    private static final int STUCK_THRESHOLD = 140; // 7 seconds * 20 ticks
    private static final int JUMP_COOLDOWN_TICKS = 40; // 2 seconds between auto-jumps

    public boolean canContinue(PlayerEntity player, int maxY) {
        // Check Y level
        if (player.getY() > maxY) {
            return false;
        }

        // Allow continuing if not on ground (might be jumping or dropping)
        // We check if the player is reasonably close to ground level
        // to distinguish between jumping/dropping and falling into void
        if (!player.isOnGround()) {
            // Check if there's ground nearby (within 3 blocks below)
            boolean hasGroundNearby = false;
            int groundDistance = 0;

            for (int i = 1; i <= 4; i++) {
                BlockPos checkPos = player.getBlockPos().down(i);
                if (!player.getWorld().getBlockState(checkPos).isAir()) {
                    hasGroundNearby = true;
                    groundDistance = i;
                    break;
                }
            }

            // Allow 1-2 block drops (normal mining drops)
            if (hasGroundNearby && groundDistance <= 2) {
                return true; // Safe drop
            }

            // If no ground nearby and falling fast, it's not safe
            if (!hasGroundNearby && player.getVelocity().y < -0.5) {
                return false;
            }

            // Check for water/lava below (immediate danger)
            BlockPos belowPos = player.getBlockPos().down();
            BlockState belowState = player.getWorld().getBlockState(belowPos);
            if (belowState.getFluidState().getFluid() == Fluids.LAVA ||
                    belowState.getFluidState().getFluid() == Fluids.WATER ||
                    belowState.getFluidState().getFluid() == Fluids.FLOWING_LAVA ||
                    belowState.getFluidState().getFluid() == Fluids.FLOWING_WATER) {
                return false; // Falling into fluid
            }

            // Otherwise, we're probably just jumping or doing a safe drop
        }

        // Check health
        if (player.getHealth() < 10) {
            return false;
        }

        // Check tool durability
        ItemStack mainHand = player.getMainHandStack();
        if (mainHand.getItem() instanceof PickaxeItem) {
            int durability = mainHand.getMaxDamage() - mainHand.getDamage();
            if (durability < 100) {
                return false;
            }
        }

        return true;
    }

    public void checkAndHandleStuck(PlayerEntity player) {
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }

        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());

        if (lastPosition == null) {
            lastPosition = currentPos;
            return;
        }

        // Check if position hasn't changed (ignoring Y for jumping)
        double horizontalDistance = Math.sqrt(
                Math.pow(currentPos.x - lastPosition.x, 2) +
                        Math.pow(currentPos.z - lastPosition.z, 2)
        );

        if (horizontalDistance < 0.1) {
            stuckTicks++;

            if (stuckTicks >= STUCK_THRESHOLD && jumpCooldown <= 0 && player.isOnGround()) {
                // Auto-jump to try to get unstuck
                player.jump();
                jumpCooldown = JUMP_COOLDOWN_TICKS;
                stuckTicks = 0; // Reset stuck counter after jump
            }
        } else {
            // Player moved, reset counter
            stuckTicks = 0;
            lastPosition = currentPos;
        }
    }

    // Deprecated - use checkAndHandleStuck instead
    @Deprecated
    public boolean checkStuck(PlayerEntity player) {
        checkAndHandleStuck(player);
        return false; // Never return true to avoid stopping the module
    }

    public void reset() {
        stuckTicks = 0;
        jumpCooldown = 0;
        lastPosition = null;
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD;
    }
}
