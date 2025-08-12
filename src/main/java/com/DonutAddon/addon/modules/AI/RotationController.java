package com.DonutAddon.addon.modules.AI;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import java.util.Random;

public class RotationController {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private float currentYaw;
    private float targetYaw;
    private float currentPitch;
    private float targetPitch;
    private float currentRotationSpeed;
    private float currentPitchSpeed;
    private boolean isRotating = false;
    private Runnable callback;

    // Settings (passed from main module)
    private boolean smoothRotation = true;
    private double baseSpeed = 4.5;
    private double acceleration = 0.8;
    private boolean humanLike = true;
    private double overshootChance = 0.3;
    private boolean preciseLanding = true;
    private double randomVariation = 0.0; // NEW: Random variation setting

    // Effective values with randomness applied (calculated once per rotation)
    private double effectiveSpeed;
    private double effectiveAcceleration;

    public void updateSettings(boolean smooth, double speed, double accel, boolean human, double overshoot) {
        this.smoothRotation = smooth;
        this.baseSpeed = speed;
        this.acceleration = accel;
        this.humanLike = human;
        this.overshootChance = overshoot;
    }

    // NEW: Method to update random variation setting
    public void updateRandomVariation(double variation) {
        this.randomVariation = variation;
    }

    // Add method to toggle precise landing
    public void setPreciseLanding(boolean precise) {
        this.preciseLanding = precise;
    }

    public void startRotation(float targetYaw, Runnable onComplete) {
        startRotation(targetYaw, 0.0f, onComplete); // Default to 0 pitch (looking straight)
    }

    public void startRotation(float targetYaw, float targetPitch, Runnable onComplete) {
        this.targetYaw = targetYaw;
        this.targetPitch = targetPitch;
        this.callback = onComplete;

        // Calculate randomized values for this rotation session
        calculateEffectiveValues();

        if (!smoothRotation) {
            // Instant rotation - always precise
            setYawAngle(targetYaw);
            setPitchAngle(targetPitch);
            if (callback != null) callback.run();
            return;
        }

        // Initialize smooth rotation
        isRotating = true;
        currentYaw = mc.player.getYaw();
        currentPitch = mc.player.getPitch();
        currentRotationSpeed = 0;
        currentPitchSpeed = 0;
    }

    // NEW: Calculate effective speed and acceleration with randomness
    private void calculateEffectiveValues() {
        if (randomVariation <= 0) {
            // No randomness, use base values
            effectiveSpeed = baseSpeed;
            effectiveAcceleration = acceleration;
            return;
        }

        // Generate random multipliers between -1 and 1
        double speedMultiplier = (random.nextDouble() * 2) - 1; // -1 to 1
        double accelMultiplier = (random.nextDouble() * 2) - 1; // -1 to 1

        // Calculate speed variation
        double speedVariation = randomVariation;
        effectiveSpeed = baseSpeed + (speedMultiplier * speedVariation);

        // Calculate acceleration variation (scaled proportionally)
        // If acceleration is 0.8 and speed is 4.5, acceleration is ~17.8% of speed
        // So acceleration randomness should be ~17.8% of speed randomness
        double accelRatio = acceleration / baseSpeed;
        double accelVariation = randomVariation * accelRatio;
        effectiveAcceleration = acceleration + (accelMultiplier * accelVariation);

        // Ensure minimum values to prevent negative or zero values
        effectiveSpeed = Math.max(0.5, effectiveSpeed);
        effectiveAcceleration = Math.max(0.1, effectiveAcceleration);

        // Cap maximum values to prevent erratic behavior
        effectiveSpeed = Math.min(baseSpeed * 2, effectiveSpeed);
        effectiveAcceleration = Math.min(acceleration * 2, effectiveAcceleration);

        // Debug output (optional)
        if (false) { // Change to true for debugging
            System.out.println(String.format("Rotation Randomness Applied: Speed %.2f -> %.2f, Accel %.2f -> %.2f",
                baseSpeed, effectiveSpeed, acceleration, effectiveAcceleration));
        }
    }

    public void update() {
        if (!isRotating) return;

        boolean yawComplete = updateYaw();
        boolean pitchComplete = updatePitch();

        // Check if both rotations are complete
        if (yawComplete && pitchComplete) {
            isRotating = false;

            // Ensure exact final positioning
            if (preciseLanding) {
                setYawAngle(targetYaw);
                setPitchAngle(targetPitch);
            }

            if (callback != null) {
                callback.run();
            }
        }
    }

    private boolean updateYaw() {
        // Calculate angle to target
        float deltaAngle = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float distance = Math.abs(deltaAngle);

        // Define threshold for when to snap to target
        float snapThreshold = preciseLanding ? 1.0f : 0.5f;

        // If we're very close and want precision, just snap to target
        if (distance < snapThreshold) {
            if (preciseLanding) {
                currentYaw = targetYaw;
                setYawAngle(targetYaw);
            }
            return true;
        }

        // Dynamic speed based on distance (using effectiveSpeed instead of baseSpeed)
        float targetSpeed;

        // Reduce randomness effect when close to target for precision
        double speedToUse = effectiveSpeed;
        if (preciseLanding && distance < 5 && randomVariation > 0) {
            // Blend between effective and base speed as we approach target
            double blendFactor = distance / 5.0; // 1 at distance 5, 0 at distance 0
            speedToUse = baseSpeed + (effectiveSpeed - baseSpeed) * blendFactor;
        }

        if (distance > 45) {
            targetSpeed = (float)(speedToUse * 1.5);
        } else if (distance > 15) {
            targetSpeed = (float)speedToUse;
        } else {
            // Slow down as we approach for precision
            targetSpeed = (float)(speedToUse * (distance / 15));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.3f : 0.5f);
        }

        // Smoothly adjust current speed (using effectiveAcceleration)
        float accel = (float)effectiveAcceleration;
        if (currentRotationSpeed < targetSpeed) {
            currentRotationSpeed = Math.min(currentRotationSpeed + accel, targetSpeed);
        } else {
            currentRotationSpeed = Math.max(currentRotationSpeed - accel, targetSpeed);
        }

        // Add human-like imperfections (disable near target for precision)
        float jitter = 0;
        float speedVariation = 1;
        if (humanLike && (!preciseLanding || distance > 5)) {
            jitter = (random.nextFloat() - 0.5f) * 0.2f;
            speedVariation = 0.9f + random.nextFloat() * 0.2f;

            // Occasional pause (not when we're close to target)
            if (random.nextFloat() < 0.02 && distance > 10) {
                currentRotationSpeed *= 0.3f;
            }
        }

        // Calculate step
        float step = Math.min(distance, currentRotationSpeed * speedVariation);
        if (deltaAngle < 0) step = -step;

        // Apply rotation
        currentYaw += step + jitter;

        // Prevent overshooting when precise landing is enabled
        if (preciseLanding) {
            float newDelta = MathHelper.wrapDegrees(targetYaw - currentYaw);
            if (Math.signum(newDelta) != Math.signum(deltaAngle)) {
                // We overshot, snap to target
                currentYaw = targetYaw;
            }
        }

        setYawAngle(currentYaw);

        return false;
    }

    private boolean updatePitch() {
        // Calculate angle to target
        float deltaAngle = targetPitch - currentPitch;
        float distance = Math.abs(deltaAngle);

        // Define threshold for when to snap to target
        float snapThreshold = preciseLanding ? 1.0f : 0.5f;

        // If we're very close and want precision, just snap to target
        if (distance < snapThreshold) {
            if (preciseLanding) {
                currentPitch = targetPitch;
                setPitchAngle(targetPitch);
            }
            return true;
        }

        // Dynamic speed based on distance (using effectiveSpeed)
        float targetSpeed;

        // Reduce randomness effect when close to target for precision
        double speedToUse = effectiveSpeed;
        if (preciseLanding && distance < 3 && randomVariation > 0) {
            // Blend between effective and base speed as we approach target
            double blendFactor = distance / 3.0;
            speedToUse = baseSpeed + (effectiveSpeed - baseSpeed) * blendFactor;
        }

        if (distance > 30) {
            targetSpeed = (float)(speedToUse * 1.2);
        } else if (distance > 10) {
            targetSpeed = (float)(speedToUse * 0.8);
        } else {
            // Slow down as we approach for precision
            targetSpeed = (float)(speedToUse * 0.8 * (distance / 10));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.25f : 0.4f);
        }

        // Smoothly adjust current speed (using effectiveAcceleration)
        float accel = (float)(effectiveAcceleration * 0.8); // Slightly slower acceleration for pitch
        if (currentPitchSpeed < targetSpeed) {
            currentPitchSpeed = Math.min(currentPitchSpeed + accel, targetSpeed);
        } else {
            currentPitchSpeed = Math.max(currentPitchSpeed - accel, targetSpeed);
        }

        // Add human-like imperfections (disable near target for precision)
        float jitter = 0;
        float speedVariation = 1;
        if (humanLike && (!preciseLanding || distance > 3)) {
            jitter = (random.nextFloat() - 0.5f) * 0.15f;
            speedVariation = 0.92f + random.nextFloat() * 0.16f;
        }

        // Calculate step
        float step = Math.min(distance, currentPitchSpeed * speedVariation);
        if (deltaAngle < 0) step = -step;

        // Apply rotation
        currentPitch += step + jitter;

        // Prevent overshooting when precise landing is enabled
        if (preciseLanding) {
            float newDelta = targetPitch - currentPitch;
            if (Math.signum(newDelta) != Math.signum(deltaAngle)) {
                // We overshot, snap to target
                currentPitch = targetPitch;
            }
        }

        setPitchAngle(currentPitch);

        return false;
    }

    private void setYawAngle(float yawAngle) {
        mc.player.setYaw(yawAngle);
        mc.player.headYaw = yawAngle;
        mc.player.bodyYaw = yawAngle;
    }

    private void setPitchAngle(float pitchAngle) {
        // Clamp pitch to valid range (-90 to 90)
        pitchAngle = MathHelper.clamp(pitchAngle, -90.0f, 90.0f);
        mc.player.setPitch(pitchAngle);
    }

    public boolean isRotating() { return isRotating; }

    // Convenience method to reset pitch to 0
    public void resetPitch(Runnable onComplete) {
        if (Math.abs(mc.player.getPitch()) > 1.0f) {
            startRotation(mc.player.getYaw(), 0.0f, onComplete);
        } else {
            // Ensure exact 0 pitch when resetting
            if (preciseLanding) {
                setPitchAngle(0.0f);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    // Get current precise values
    public float getCurrentYaw() { return currentYaw; }
    public float getCurrentPitch() { return currentPitch; }
    public float getTargetYaw() { return targetYaw; }
    public float getTargetPitch() { return targetPitch; }

    // Get effective values (for debugging)
    public double getEffectiveSpeed() { return effectiveSpeed; }
    public double getEffectiveAcceleration() { return effectiveAcceleration; }
}
