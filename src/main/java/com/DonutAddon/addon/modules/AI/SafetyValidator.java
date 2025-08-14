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
    private int unstuckAttempts = 0;
    private boolean needsModuleReset = false;

    // Mining down specific tracking
    private double lastYPosition = 0;
    private int verticalStuckTicks = 0;
    private int miningDownAttempts = 0;
    private Vec3d lastMiningDownPosition;

    private boolean jumpedWhileMoving = false;
    private int jumpFollowUpTicks = 0;

    // NEW: Recovery tracking
    private int recoveryGracePeriod = 0;  // Grace period after recovery attempt
    private double recoveryStartY = 0;     // Y position when recovery started
    private boolean inRecovery = false;    // Currently attempting recovery
    private static final int RECOVERY_GRACE_TICKS = 40; // 2 seconds to let recovery work

    // Different thresholds for different modes
    private static final int STUCK_THRESHOLD = 60; // Normal mining
    private static final int MINING_DOWN_STUCK_THRESHOLD = 40; // Faster detection for mining down
    private static final int JUMP_COOLDOWN_TICKS = 20;
    private static final double MIN_DURABILITY_PERCENT = 0.10;
    private static final double MOVEMENT_THRESHOLD = 1.0;
    private static final double VERTICAL_MOVEMENT_THRESHOLD = 0.5; // For mining down


    // Track position history for better stuck detection
    private Vec3d positionHistory[] = new Vec3d[20];
    private int historyIndex = 0;

    // Mining mode enum
    public enum MiningMode {
        NORMAL,           // Regular horizontal mining
        MINING_DOWN,      // Vertical mining down
        MOVING_TO_TARGET  // Moving to a specific location
    }

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
     * Enhanced stuck detection with mode-aware recovery
     * @param player The player entity
     * @param mode The current mining mode
     * @return StuckRecoveryAction indicating what the module should do
     */
    public StuckRecoveryAction checkAndHandleStuck(PlayerEntity player, MiningMode mode) {
        if (jumpCooldown > 0) {
            jumpCooldown--;
        }

        // Handle recovery grace period
        if (recoveryGracePeriod > 0) {
            recoveryGracePeriod--;

            // During grace period, check if we've made progress
            if (mode == MiningMode.MINING_DOWN && recoveryGracePeriod == 1) {
                // Grace period ending, check if recovery worked
                double currentY = player.getY();
                double progressMade = Math.abs(recoveryStartY - currentY);

                if (progressMade < 1.0) {
                    // Recovery didn't work, will try next attempt on next stuck detection
                    System.out.println("Recovery attempt " + miningDownAttempts + " failed (moved only " +
                        String.format("%.2f", progressMade) + " blocks)");
                    inRecovery = false;
                } else {
                    // Recovery worked! Reset attempts
                    System.out.println("Recovery successful! Moved " + String.format("%.2f", progressMade) + " blocks");
                    miningDownAttempts = 0;
                    inRecovery = false;
                }
            }

            return StuckRecoveryAction.NONE; // Wait during grace period
        }

        inRecovery = false; // Grace period over

        switch (mode) {
            case MINING_DOWN:
                return checkMiningDownStuck(player);
            case MOVING_TO_TARGET:
                return checkMovementStuck(player, false); // No jumping for detour movement
            case NORMAL:
            default:
                return checkMovementStuck(player, true); // Allow jumping for normal mining
        }
    }

    /**
     * Check for stuck while mining down (vertical movement)
     */
    private StuckRecoveryAction checkMiningDownStuck(PlayerEntity player) {
        double currentY = player.getY();

        // Initialize if first check
        if (lastMiningDownPosition == null) {
            lastMiningDownPosition = player.getPos();
            lastYPosition = currentY;
            return StuckRecoveryAction.NONE;
        }

        // Check vertical movement
        double verticalDistance = Math.abs(currentY - lastYPosition);

        // Also check horizontal movement (shouldn't move much horizontally when mining down)
        double horizontalDistance = Math.sqrt(
            Math.pow(player.getX() - lastMiningDownPosition.x, 2) +
                Math.pow(player.getZ() - lastMiningDownPosition.z, 2)
        );

        // Consider stuck if not moving down OR moving too much horizontally
        boolean isStuck = verticalDistance < VERTICAL_MOVEMENT_THRESHOLD ||
            horizontalDistance > 2.0; // Moved more than 2 blocks horizontally means something's wrong

        if (isStuck) {
            verticalStuckTicks++;

            System.out.println("MINING DOWN STUCK: Y-movement=" + String.format("%.3f", verticalDistance) +
                ", H-movement=" + String.format("%.3f", horizontalDistance) +
                ", Ticks=" + verticalStuckTicks +
                ", Attempts=" + miningDownAttempts);

            if (verticalStuckTicks >= MINING_DOWN_STUCK_THRESHOLD) {
                // Increment attempts BEFORE returning action
                miningDownAttempts++;

                // Reset stuck counter for next detection cycle
                verticalStuckTicks = 0;

                // Store recovery start position
                recoveryStartY = currentY;
                inRecovery = true;

                // Set grace period to allow recovery to work
                recoveryGracePeriod = RECOVERY_GRACE_TICKS;

                // IMPORTANT: Update reference position so we don't immediately re-detect as stuck
                lastYPosition = currentY;
                lastMiningDownPosition = player.getPos();

                if (miningDownAttempts == 1) {
                    // FIRST ATTEMPT: Retoggle keys
                    System.out.println("=== MINING DOWN RECOVERY: ATTEMPT 1 - RETOGGLE KEYS ===");
                    return StuckRecoveryAction.RETOGGLE_KEYS;

                } else if (miningDownAttempts == 2) {
                    // SECOND ATTEMPT: Move horizontally and retry
                    System.out.println("=== MINING DOWN RECOVERY: ATTEMPT 2 - MOVE AND RETRY ===");
                    return StuckRecoveryAction.MOVE_AND_RETRY;

                } else if (miningDownAttempts == 3) {
                    // THIRD ATTEMPT: Find new spot
                    System.out.println("=== MINING DOWN RECOVERY: ATTEMPT 3 - FIND NEW SPOT ===");
                    return StuckRecoveryAction.FIND_NEW_SPOT;

                } else if (miningDownAttempts >= 4) {
                    // FINAL ATTEMPT: Request module reset
                    System.out.println("=== MINING DOWN RECOVERY: FINAL - REQUEST MODULE RESET ===");
                    needsModuleReset = true;
                    return StuckRecoveryAction.NEEDS_RESET;
                }
            }
        } else {
            // Player moved successfully
            if (verticalStuckTicks > 0) {
                System.out.println("Vertical movement detected (" + String.format("%.2f", verticalDistance) +
                    " blocks), resetting stuck counter");
            }
            verticalStuckTicks = 0;

            // Only reset attempts if we've made significant progress
            if (verticalDistance > 1.0) {
                miningDownAttempts = 0;
            }

            lastYPosition = currentY;
            lastMiningDownPosition = player.getPos();
        }

        return StuckRecoveryAction.NONE;
    }

    /**
     * Check for stuck while moving horizontally
     * @param allowJumping Whether jumping is allowed as recovery
     */
    private StuckRecoveryAction checkMovementStuck(PlayerEntity player, boolean allowJumping) {
        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());

        // Update position history
        positionHistory[historyIndex] = currentPos;
        historyIndex = (historyIndex + 1) % positionHistory.length;

        if (lastPosition == null) {
            lastPosition = currentPos;
            return StuckRecoveryAction.NONE;
        }

        // Check horizontal movement
        double horizontalDistance = Math.sqrt(
            Math.pow(currentPos.x - lastPosition.x, 2) +
                Math.pow(currentPos.z - lastPosition.z, 2)
        );

        // Check longer term movement
        Vec3d oldPos = positionHistory[(historyIndex + 1) % positionHistory.length];
        double longerTermMovement = 0;
        if (oldPos != null) {
            longerTermMovement = Math.sqrt(
                Math.pow(currentPos.x - oldPos.x, 2) +
                    Math.pow(currentPos.z - oldPos.z, 2)
            );
        }

        // Check if we moved after jumping (for attempt 1 follow-up)
        if (jumpedWhileMoving && jumpFollowUpTicks > 0) {
            jumpFollowUpTicks--;

            // Check if we've moved horizontally after jumping
            if (horizontalDistance > MOVEMENT_THRESHOLD) {
                System.out.println("Movement detected after jump+forward, stopping movement");
                jumpedWhileMoving = false;
                jumpFollowUpTicks = 0;
                // Return a signal to stop movement
                return StuckRecoveryAction.STOP_MOVEMENT;
            }

            if (jumpFollowUpTicks == 0) {
                // Timeout waiting for movement after jump
                jumpedWhileMoving = false;
                System.out.println("No movement after jump+forward, proceeding to next attempt");
            }
        }

        boolean isStuck = horizontalDistance < MOVEMENT_THRESHOLD &&
            (oldPos == null || longerTermMovement < 0.5);

        if (isStuck) {
            stuckTicks++;

            System.out.println("MOVEMENT STUCK: Ticks=" + stuckTicks +
                ", Attempts=" + unstuckAttempts +
                ", Movement=" + String.format("%.3f", horizontalDistance) +
                ", AllowJump=" + allowJumping);

            if (stuckTicks >= STUCK_THRESHOLD && jumpCooldown <= 0) {
                unstuckAttempts++;

                // Reset stuck counter and update position
                stuckTicks = 0;
                lastPosition = currentPos;

                if (unstuckAttempts == 1) {
                    if (allowJumping && player.isOnGround()) {
                        // ATTEMPT 1: Jump while moving forward
                        System.out.println("=== STUCK RECOVERY: ATTEMPT 1 - JUMP WHILE MOVING FORWARD ===");
                        jumpedWhileMoving = true;
                        jumpFollowUpTicks = 20; // Monitor for 1 second
                        jumpCooldown = JUMP_COOLDOWN_TICKS;
                        return StuckRecoveryAction.JUMP_FORWARD;
                    } else {
                        // Can't jump, try recalculating path
                        System.out.println("=== STUCK RECOVERY: ATTEMPT 1 - RECALCULATE PATH (no jump) ===");
                        return StuckRecoveryAction.RECALCULATE_PATH;
                    }
                } else if (unstuckAttempts == 2) {
                    // ATTEMPT 2: Try to find alternative spot or recalculate
                    if (!allowJumping) {
                        System.out.println("=== STUCK RECOVERY: ATTEMPT 2 - FIND NEW SPOT ===");
                        return StuckRecoveryAction.FIND_NEW_SPOT;
                    } else {
                        System.out.println("=== STUCK RECOVERY: ATTEMPT 2 - RECALCULATE PATH ===");
                        return StuckRecoveryAction.RECALCULATE_PATH;
                    }
                } else if (unstuckAttempts == 3) {
                    // ATTEMPT 3: Path is blocked, trigger RTP sequence
                    System.out.println("=== STUCK RECOVERY: ATTEMPT 3 - PATH BLOCKED, TRIGGER RTP ===");
                    return StuckRecoveryAction.PATH_BLOCKED_RTP;
                } else if (unstuckAttempts >= 4) {
                    // FINAL ATTEMPT: Request module reset
                    System.out.println("=== STUCK RECOVERY: FINAL - REQUESTING MODULE RESET ===");
                    needsModuleReset = true;
                    jumpCooldown = JUMP_COOLDOWN_TICKS * 2;
                    return StuckRecoveryAction.NEEDS_RESET;
                }
            }
        } else {
            // Player moved successfully
            if (stuckTicks > 0) {
                System.out.println("Movement detected, resetting stuck counter");
            }
            stuckTicks = 0;
            unstuckAttempts = 0;
            needsModuleReset = false;
            jumpedWhileMoving = false;
            jumpFollowUpTicks = 0;
            lastPosition = currentPos;
        }

        return StuckRecoveryAction.NONE;
    }

    /**
     * Reset mining down tracking
     */
    public void resetMiningDown() {
        verticalStuckTicks = 0;
        miningDownAttempts = 0;
        lastMiningDownPosition = null;
        lastYPosition = 0;
        recoveryGracePeriod = 0;
        inRecovery = false;
        recoveryStartY = 0;
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
        miningDownAttempts = 0;
        verticalStuckTicks = 0;
        recoveryGracePeriod = 0;
        inRecovery = false;
    }

    public void reset() {
        stuckTicks = 0;
        jumpCooldown = 0;
        unstuckAttempts = 0;
        needsModuleReset = false;
        lastPosition = null;
        positionHistory = new Vec3d[20];
        historyIndex = 0;

        // Reset mining down specific
        resetMiningDown();
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_THRESHOLD || verticalStuckTicks >= MINING_DOWN_STUCK_THRESHOLD;
    }

    public int getUnstuckAttempts() {
        return Math.max(unstuckAttempts, miningDownAttempts);
    }

    /**
     * Check if currently in recovery (for external modules to know)
     */
    public boolean isInRecovery() {
        return inRecovery || recoveryGracePeriod > 0;
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
        NONE,
        JUMP_FORWARD,      // New: Jump while moving forward
        STOP_MOVEMENT,     // New: Stop after successful jump movement
        PATH_BLOCKED_RTP,  // New: Trigger RTP sequence
        RETOGGLE_KEYS,
        MOVE_AND_RETRY,
        FIND_NEW_SPOT,
        RECALCULATE_PATH,
        NEEDS_RESET
    }
}
