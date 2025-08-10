package io.github.stashya.donutaddon.modules.AI;

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
    private boolean preciseLanding = true; // New setting for precise final positioning

    public void updateSettings(boolean smooth, double speed, double accel, boolean human, double overshoot) {
        this.smoothRotation = smooth;
        this.baseSpeed = speed;
        this.acceleration = accel;
        this.humanLike = human;
        this.overshootChance = overshoot;
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

        // Dynamic speed based on distance
        float targetSpeed;
        if (distance > 45) {
            targetSpeed = (float)(baseSpeed * 1.5);
        } else if (distance > 15) {
            targetSpeed = (float)baseSpeed;
        } else {
            // Slow down as we approach for precision
            targetSpeed = (float)(baseSpeed * (distance / 15));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.3f : 0.5f);
        }

        // Smoothly adjust current speed
        float accel = (float)acceleration;
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
        // Dynamic speed based on distance (slightly slower than yaw for realism)
        float targetSpeed;
        if (distance > 30) {
            targetSpeed = (float)(baseSpeed * 1.2);
        } else if (distance > 10) {
            targetSpeed = (float)(baseSpeed * 0.8);
        } else {
            // Slow down as we approach for precision
            targetSpeed = (float)(baseSpeed * 0.8 * (distance / 10));
            targetSpeed = Math.max(targetSpeed, preciseLanding ? 0.25f : 0.4f);
        }

        // Smoothly adjust current speed
        float accel = (float)(acceleration * 0.8); // Slightly slower acceleration for pitch
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
}
