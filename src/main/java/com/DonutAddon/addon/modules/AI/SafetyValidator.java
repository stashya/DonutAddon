package com.DonutAddon.addon.modules.AI;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.math.Vec3d;

public class SafetyValidator {
    private Vec3d lastPosition;
    private int stuckTicks = 0;
    private boolean hasJumped = false;
    private static final int STUCK_THRESHOLD = 140; // 7 seconds * 20 ticks

    public boolean canContinue(PlayerEntity player, int maxY) {
        // Check Y level
        if (player.getY() > maxY) {
            return false;
        }

        // Check if player is on ground
        if (!player.isOnGround()) {
            return false;
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

    public boolean checkStuck(PlayerEntity player) {
        Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());

        if (lastPosition == null) {
            lastPosition = currentPos;
            return false;
        }

        // Check if position hasn't changed (ignoring rotation)
        double distance = currentPos.distanceTo(lastPosition);

        if (distance < 0.1) {
            stuckTicks++;

            if (stuckTicks >= STUCK_THRESHOLD && !hasJumped && player.isOnGround()) {
                hasJumped = true;
                return true; // Should jump
            }
        } else {
            // Player moved, reset counters
            stuckTicks = 0;
            hasJumped = false;
            lastPosition = currentPos;
        }

        return false;
    }

    public void reset() {
        stuckTicks = 0;
        hasJumped = false;
        lastPosition = null;
    }

    public boolean isInValidMiningArea(PlayerEntity player) {
        return player.getY() <= -50 && player.isOnGround();
    }

    public boolean hasValidTool(PlayerEntity player) {
        ItemStack mainHand = player.getMainHandStack();
        return mainHand.getItem() instanceof PickaxeItem;
    }
}
