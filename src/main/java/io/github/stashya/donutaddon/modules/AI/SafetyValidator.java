package io.github.stashya.donutaddon.modules.AI;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class SafetyValidator {
    private Vec3d lastPosition;
    private int stuckTicks = 0;
    private int jumpCooldown = 0;
    private static final int STUCK_THRESHOLD = 140; // 7 seconds * 20 ticks
    private static final int JUMP_COOLDOWN_TICKS = 40; // 2 seconds between auto-jumps
    private static final double MIN_DURABILITY_PERCENT = 0.10; // 10% minimum durability

    public boolean canContinue(PlayerEntity player, int maxY) {
        // Check Y level
        if (player.getY() > maxY) {
            return false;
        }

        // Allow continuing if not on ground (might be jumping or dropping)
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
        }

        // Check health
        if (player.getHealth() < 10) {
            System.out.println("WARNING: Low health (" + player.getHealth() + "), stopping");
            return false;
        }

        // Check tool durability (UPDATED to percentage-based)
        ItemStack mainHand = player.getMainHandStack();
        if (isMiningTool(mainHand)) {
            int currentDamage = mainHand.getDamage();
            int maxDamage = mainHand.getMaxDamage();

            if (maxDamage > 0) { // Ensure the item has durability
                int remainingDurability = maxDamage - currentDamage;
                double durabilityPercent = (double) remainingDurability / maxDamage;

                if (durabilityPercent <= MIN_DURABILITY_PERCENT) {
                    System.out.println("WARNING: Tool durability critically low!");
                    System.out.println("  Current durability: " + remainingDurability + "/" + maxDamage);
                    System.out.println("  Percentage: " + String.format("%.1f%%", durabilityPercent * 100));
                    return false;
                }

                // Optional: Warn when getting close to the threshold
                if (durabilityPercent <= 0.15 && durabilityPercent > MIN_DURABILITY_PERCENT) {
                    System.out.println("CAUTION: Tool durability at " +
                        String.format("%.1f%%", durabilityPercent * 100) +
                        " - will stop at 10%");
                }
            }
        } else {
            // No valid mining tool in hand
            System.out.println("WARNING: No mining tool in main hand!");
            return false;
        }

        return true;
    }

    private boolean isMiningTool(ItemStack itemStack) {
        if (itemStack.isEmpty()) return false;
        return itemStack.getItem() instanceof MiningToolItem ||
            itemStack.getItem() instanceof ShearsItem ||
            itemStack.getItem() instanceof PickaxeItem;
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

    // Helper method to get current tool durability percentage
    public static double getToolDurabilityPercent(ItemStack tool) {
        if (tool.isEmpty() || tool.getMaxDamage() == 0) {
            return 1.0; // No tool or unbreakable tool = 100%
        }

        int remainingDurability = tool.getMaxDamage() - tool.getDamage();
        return (double) remainingDurability / tool.getMaxDamage();
    }
}
