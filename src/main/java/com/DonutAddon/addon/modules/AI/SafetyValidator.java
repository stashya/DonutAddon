package com.DonutAddon.addon.modules.AI;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluids;

public class SafetyValidator {
    private Vec3d lastPosition;
    private int stuckTicks = 0;
    private int jumpCooldown = 0;
    private int unstuckAttempts = 0;  // NEW: Track recovery attempts
    private boolean needsModuleReset = false;  // NEW: Flag for module reset

    // Adjusted thresholds for faster response
    private static final int STUCK_THRESHOLD = 60; // Reduced from 140 to 60 ticks (3 seconds)
    private static final int JUMP_COOLDOWN_TICKS = 20; // Reduced from 40 to 20 ticks (1 second)
    private static final double MIN_DURABILITY_PERCENT = 0.10;
    private static final double MOVEMENT_THRESHOLD = 1; // More sensitive stuck detection

    // NEW: Track position history for better stuck detection
    private Vec3d positionHistory[] = new Vec3d[20]; // Track last second of positions
    private int historyIndex = 0;

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

//        // Check health
//        if (player.getHealth() < 4) {
//            System.out.println("WARNING: Low health (" + player.getHealth() + "), stopping");
//            return false;
//        }

        // Check tool durability
        ItemStack mainHand = player.getMainHandStack();
        if (isMiningTool(mainHand)) {
            int currentDamage = mainHand.getDamage();
            int maxDamage = mainHand.getMaxDamage();

            if (maxDamage > 0) {
                int remainingDurability = maxDamage - currentDamage;
                double durabilityPercent = (double) remainingDurability / maxDamage;

                if (durabilityPercent <= MIN_DURABILITY_PERCENT) {
                    System.out.println("WARNING: Tool durability critically low!");
                    System.out.println("  Current durability: " + remainingDurability + "/" + maxDamage);
                    System.out.println("  Percentage: " + String.format("%.1f%%", durabilityPercent * 100));
                    return false;
                }

                if (durabilityPercent <= 0.15 && durabilityPercent > MIN_DURABILITY_PERCENT) {
                    System.out.println("CAUTION: Tool durability at " +
                        String.format("%.1f%%", durabilityPercent * 100) +
                        " - will stop at 10%");
                }
            }
        } else {
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

    /**
     * Enhanced stuck detection with two-attempt recovery system
     * @return StuckRecoveryAction indicating what the module should do
     */
    public StuckRecoveryAction checkAndHandleStuck(PlayerEntity player) {
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }

        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());

        // Update position history
        positionHistory[historyIndex] = currentPos;
        historyIndex = (historyIndex + 1) % positionHistory.length;

        if (lastPosition == null) {
            lastPosition = currentPos;
            return StuckRecoveryAction.NONE;
        }

        // Check horizontal movement (more sensitive)
        double horizontalDistance = Math.sqrt(
            Math.pow(currentPos.x - lastPosition.x, 2) +
                Math.pow(currentPos.z - lastPosition.z, 2)
        );

        // Also check against position from 1 second ago for better stuck detection
        Vec3d oldPos = positionHistory[(historyIndex + 1) % positionHistory.length];
        double longerTermMovement = 0;
        if (oldPos != null) {
            longerTermMovement = Math.sqrt(
                Math.pow(currentPos.x - oldPos.x, 2) +
                    Math.pow(currentPos.z - oldPos.z, 2)
            );
        }

        // Consider stuck if both short-term and long-term movement are minimal
        boolean isStuck = horizontalDistance < MOVEMENT_THRESHOLD &&
            (oldPos == null || longerTermMovement < 0.5);

        if (isStuck) {
            stuckTicks++;

            System.out.println("STUCK DETECTION: Ticks=" + stuckTicks +
                ", Attempts=" + unstuckAttempts +
                ", Movement=" + String.format("%.3f", horizontalDistance));

            if (stuckTicks >= STUCK_THRESHOLD && jumpCooldown <= 0) {
                unstuckAttempts++;

                if (unstuckAttempts == 1) {
                    // FIRST ATTEMPT: Just jump
                    System.out.println("=== STUCK RECOVERY: ATTEMPT 1 - JUMPING ===");
                    if (player.isOnGround()) {
                        player.jump();
                        jumpCooldown = JUMP_COOLDOWN_TICKS;
                        stuckTicks = 0; // Reset counter after jump
                        return StuckRecoveryAction.JUMPED;
                    }
                } else if (unstuckAttempts >= 2) {
                    // SECOND ATTEMPT: Request module reset
                    System.out.println("=== STUCK RECOVERY: ATTEMPT 2 - REQUESTING MODULE RESET ===");
                    System.out.println("Jump didn't work, need to recalculate path");
                    needsModuleReset = true;
                    stuckTicks = 0;
                    jumpCooldown = JUMP_COOLDOWN_TICKS * 2; // Longer cooldown after reset
                    return StuckRecoveryAction.NEEDS_RESET;
                }
            }
        } else {
            // Player moved successfully
            if (stuckTicks > 0) {
                System.out.println("Movement detected, resetting stuck counter");
            }
            stuckTicks = 0;
            unstuckAttempts = 0; // Reset attempts when moving normally
            needsModuleReset = false;
            lastPosition = currentPos;
        }

        return StuckRecoveryAction.NONE;
    }

    /**
     * Check if module reset is needed
     */
    public boolean needsModuleReset() {
        return needsModuleReset;
    }

    /**
     * Acknowledge that module reset has been performed
     */
    public void acknowledgeReset() {
        needsModuleReset = false;
        unstuckAttempts = 0;
        stuckTicks = 0;
        // Don't reset position to allow immediate detection if still stuck
    }

    public void reset() {
        stuckTicks = 0;
        jumpCooldown = 0;
        unstuckAttempts = 0;
        needsModuleReset = false;
        lastPosition = null;
        positionHistory = new Vec3d[20];
        historyIndex = 0;
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD;
    }

    public int getUnstuckAttempts() {
        return unstuckAttempts;
    }

    // Helper method to get current tool durability percentage
    public static double getToolDurabilityPercent(ItemStack tool) {
        if (tool.isEmpty() || tool.getMaxDamage() == 0) {
            return 1.0; // No tool or unbreakable tool = 100%
        }

        int remainingDurability = tool.getMaxDamage() - tool.getDamage();
        return (double) remainingDurability / tool.getMaxDamage();
    }

    /**
     * Enum for recovery actions
     */
    public enum StuckRecoveryAction {
        NONE,         // Not stuck, continue normally
        JUMPED,       // Just jumped, wait to see if it helps
        NEEDS_RESET   // Need to reset module state
    }
}
