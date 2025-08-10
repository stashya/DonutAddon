package io.github.stashya.donutaddon.modules.AI;

import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;

public class SimpleSneakCentering {
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private static final double TOLERANCE = 0.15;

    private boolean active = false;
    private BlockPos targetBlock = null;
    private double targetX;
    private double targetZ;

    // Debug tracking
    private int tickCount = 0;
    private double lastDistanceToTarget = 0;

    public boolean startCentering() {
        if (mc.player == null) return false;

        // Get the block we're standing on
        targetBlock = mc.player.getBlockPos();

        // Calculate center of that block
        targetX = targetBlock.getX() + 0.5;
        targetZ = targetBlock.getZ() + 0.5;

        // Check if already centered
        double offsetX = Math.abs(mc.player.getX() - targetX);
        double offsetZ = Math.abs(mc.player.getZ() - targetZ);

        System.out.println("=== CENTERING START ===");
        System.out.println("Player pos: " + mc.player.getX() + ", " + mc.player.getZ());
        System.out.println("Target block: " + targetBlock);
        System.out.println("Target center: " + targetX + ", " + targetZ);
        System.out.println("Initial offset: X=" + offsetX + ", Z=" + offsetZ);

        if (offsetX <= TOLERANCE && offsetZ <= TOLERANCE) {
            System.out.println("Already centered!");
            return false;
        }

        active = true;
        tickCount = 0;
        lastDistanceToTarget = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
        return true;
    }

    public boolean tick() {
        if (!active || mc.player == null) return false;

        tickCount++;

        // Calculate world offset from target
        double worldOffsetX = mc.player.getX() - targetX;
        double worldOffsetZ = mc.player.getZ() - targetZ;

        // Calculate distance
        double currentDistance = Math.sqrt(worldOffsetX * worldOffsetX + worldOffsetZ * worldOffsetZ);

        System.out.println("\n--- Tick " + tickCount + " ---");
        System.out.println("Player pos: " + mc.player.getX() + ", " + mc.player.getZ());
        System.out.println("World offset: X=" + worldOffsetX + ", Z=" + worldOffsetZ);
        System.out.println("Distance to target: " + currentDistance + " (was " + lastDistanceToTarget + ")");

        // Check if getting further away
        if (currentDistance > lastDistanceToTarget + 0.01) {
            System.out.println("WARNING: Moving AWAY from target!");
        }
        lastDistanceToTarget = currentDistance;

        // Check if we're centered (within tolerance)
        if (Math.abs(worldOffsetX) <= TOLERANCE && Math.abs(worldOffsetZ) <= TOLERANCE) {
            System.out.println("CENTERED! Stopping.");
            stopCentering();
            return false;
        }

        // Reset all keys first
        releaseAllKeys();

        // Always sneak for precise movement
        setPressed(mc.options.sneakKey, true);

        // Get player yaw and convert to radians
        float yaw = mc.player.getYaw();
        double yawRad = Math.toRadians(yaw);

        System.out.println("Player yaw: " + yaw + "° (" + getCardinalDirection(yaw) + ")");

        // Calculate movement needed (negative of offset)
        double moveX = -worldOffsetX;
        double moveZ = -worldOffsetZ;

        // Transform to player-relative coordinates
        // When yaw=0 (facing south), forward moves +Z, right moves -X
        // When yaw=90 (facing west), forward moves -X, right moves -Z
        // When yaw=180 (facing north), forward moves -Z, right moves +X
        // When yaw=-90 (facing east), forward moves +X, right moves +Z
        double relativeForward = moveX * (-Math.sin(yawRad)) + moveZ * Math.cos(yawRad);
        double relativeStrafe = moveX * (-Math.cos(yawRad)) + moveZ * (-Math.sin(yawRad));

        System.out.println("Relative movement needed: Forward=" + relativeForward + ", Strafe=" + relativeStrafe);

        // Track which keys we press
        StringBuilder keysPressed = new StringBuilder("Keys pressed: ");

        // Forward/Backward
        if (Math.abs(relativeForward) > TOLERANCE * 0.5) {
            if (relativeForward > 0) {
                setPressed(mc.options.forwardKey, true);
                keysPressed.append("FORWARD ");
            } else {
                setPressed(mc.options.backKey, true);
                keysPressed.append("BACK ");
            }
        }

        // Left/Right
        if (Math.abs(relativeStrafe) > TOLERANCE * 0.5) {
            if (relativeStrafe > 0) {
                setPressed(mc.options.rightKey, true);
                keysPressed.append("RIGHT ");
            } else {
                setPressed(mc.options.leftKey, true);
                keysPressed.append("LEFT ");
            }
        }

        keysPressed.append("SNEAK");
        System.out.println(keysPressed.toString());

        // Safety check - stop after 100 ticks (5 seconds)
        if (tickCount > 100) {
            System.out.println("ERROR: Centering timed out after 100 ticks!");
            stopCentering();
            return false;
        }

        return true;
    }

    private String getCardinalDirection(float yaw) {
        // Normalize yaw to 0-360
        float normalizedYaw = ((yaw % 360) + 360) % 360;

        if (normalizedYaw >= 315 || normalizedYaw < 45) return "SOUTH";
        else if (normalizedYaw >= 45 && normalizedYaw < 135) return "WEST";
        else if (normalizedYaw >= 135 && normalizedYaw < 225) return "NORTH";
        else return "EAST";
    }

    public void stopCentering() {
        System.out.println("=== CENTERING STOPPED ===\n");
        active = false;
        targetBlock = null;
        releaseAllKeys();
        tickCount = 0;
    }

    private void releaseAllKeys() {
        setPressed(mc.options.forwardKey, false);
        setPressed(mc.options.backKey, false);
        setPressed(mc.options.leftKey, false);
        setPressed(mc.options.rightKey, false);
        setPressed(mc.options.sneakKey, false);
    }

    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);  // Add this line!
    }

    public boolean isCentering() {
        return active;
    }

    public boolean isDone() {
        if (!active || mc.player == null) return true;

        double offsetX = Math.abs(mc.player.getX() - targetX);
        double offsetZ = Math.abs(mc.player.getZ() - targetZ);

        return offsetX <= TOLERANCE && offsetZ <= TOLERANCE;
    }
}
