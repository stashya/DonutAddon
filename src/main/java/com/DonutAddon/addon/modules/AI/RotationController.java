package com.DonutAddon.addon.modules.AI;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import java.util.Random;

public class RotationController {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private float currentYaw;
    private float targetYaw;
    private float currentRotationSpeed;
    private boolean isRotating = false;
    private Runnable callback;

    // Rotation state for smooth rotation
    private int overshootTicks = 0;
    private float overshootAmount = 0;

    // Settings (passed from main module)
    private boolean smoothRotation = true;
    private double baseSpeed = 4.5;
    private double acceleration = 0.8;
    private boolean humanLike = true;
    private double overshootChance = 0.3;

    public void updateSettings(boolean smooth, double speed, double accel, boolean human, double overshoot) {
        this.smoothRotation = smooth;
        this.baseSpeed = speed;
        this.acceleration = accel;
        this.humanLike = human;
        this.overshootChance = overshoot;
    }

    public void startRotation(float targetYaw, Runnable onComplete) {
        this.targetYaw = targetYaw;
        this.callback = onComplete;

        if (!smoothRotation) {
            // Instant rotation
            setYawAngle(targetYaw);
            if (callback != null) callback.run();
            return;
        }

        // Initialize smooth rotation
        isRotating = true;
        currentYaw = mc.player.getYaw();
        currentRotationSpeed = 0;

        // Setup overshoot if human-like
        if (humanLike && random.nextDouble() < overshootChance) {
            float totalRotation = MathHelper.wrapDegrees(targetYaw - currentYaw);
            overshootAmount = (3 + random.nextFloat() * 4) * (totalRotation > 0 ? 1 : -1);
            overshootTicks = 10 + random.nextInt(15);
        } else {
            overshootAmount = 0;
            overshootTicks = 0;
        }
    }

    public void update() {
        if (!isRotating) return;

        // Calculate angle to target
        float actualTarget = targetYaw;
        if (overshootTicks > 0) {
            actualTarget = targetYaw + overshootAmount;
            overshootTicks--;
        }

        float deltaAngle = MathHelper.wrapDegrees(actualTarget - currentYaw);
        float distance = Math.abs(deltaAngle);

        // Dynamic speed based on distance
        float targetSpeed;
        if (distance > 45) {
            targetSpeed = (float)(baseSpeed * 1.5);
        } else if (distance > 15) {
            targetSpeed = (float)baseSpeed;
        } else {
            targetSpeed = (float)(baseSpeed * (distance / 15));
            targetSpeed = Math.max(targetSpeed, 0.5f);
        }

        // Smoothly adjust current speed
        float accel = (float)acceleration;
        if (currentRotationSpeed < targetSpeed) {
            currentRotationSpeed = Math.min(currentRotationSpeed + accel, targetSpeed);
        } else {
            currentRotationSpeed = Math.max(currentRotationSpeed - accel, targetSpeed);
        }

        // Add human-like imperfections
        float jitter = 0;
        float speedVariation = 1;
        if (humanLike) {
            jitter = (random.nextFloat() - 0.5f) * 0.2f;
            speedVariation = 0.9f + random.nextFloat() * 0.2f;

            // Occasional pause
            if (random.nextFloat() < 0.02 && distance > 10) {
                currentRotationSpeed *= 0.3f;
            }
        }

        // Calculate step
        float step = Math.min(distance, currentRotationSpeed * speedVariation);
        if (deltaAngle < 0) step = -step;

        // Apply rotation
        currentYaw += step + jitter;
        setYawAngle(currentYaw);

        // Check if we've reached the target
        if (distance < 0.5f || (overshootTicks == 0 && distance < 2)) {
            setYawAngle(targetYaw);
            isRotating = false;
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void setYawAngle(float yawAngle) {
        mc.player.setYaw(yawAngle);
        mc.player.headYaw = yawAngle;
        mc.player.bodyYaw = yawAngle;
    }

    public boolean isRotating() { return isRotating; }
}
