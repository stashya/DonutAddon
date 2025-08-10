package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

import java.util.Random;

public class AutoRocket extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> delay = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay")
        .description("Base delay between firework usage in seconds.")
        .defaultValue(4.0)
        .min(0.5)
        .max(10.0)
        .sliderMin(0.5)
        .sliderMax(8.0)
        .build()
    );

    private final Setting<Double> delayVariation = sgGeneral.add(new DoubleSetting.Builder()
        .name("delay-variation")
        .description("Random variation in delay (± seconds) to look more human.")
        .defaultValue(0.5)
        .min(0.0)
        .max(2.0)
        .sliderMin(0.0)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<Boolean> checkDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("check-durability")
        .description("Stop using fireworks when elytra durability is low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minDurability = sgGeneral.add(new IntSetting.Builder()
        .name("min-durability")
        .description("Minimum elytra durability before stopping firework usage.")
        .defaultValue(10)
        .min(1)
        .max(100)
        .visible(checkDurability::get)
        .build()
    );

    private final Setting<Boolean> durabilityNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("durability-notification")
        .description("Show toast notifications for low elytra durability.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> outOfRocketsNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("out-of-rockets-notification")
        .description("Show a toast notification when out of firework rockets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> returnToOriginalSlot = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-slot")
        .description("Return to original slot when out of rockets or stop flying.")
        .defaultValue(true)
        .build()
    );

    private long lastFireworkTime = 0L;
    private boolean lowDurabilityNotified = false;
    private boolean criticalDurabilityNotified = false;
    private boolean outOfRocketsNotified = false;
    private int currentRocketSlot = -1;
    private int originalSlot = -1;
    private double currentDelay;
    private final Random random = new Random();

    public AutoRocket() {
        super(DonutAddon.CATEGORY, "auto-rocket", "Automatically uses firework rockets for elytra flying.");
    }

    @Override
    public void onActivate() {
        lastFireworkTime = 0L;
        lowDurabilityNotified = false;
        criticalDurabilityNotified = false;
        outOfRocketsNotified = false;
        currentRocketSlot = -1;
        originalSlot = -1;
        generateNewDelay();
    }

    @Override
    public void onDeactivate() {
        // Return to original slot when disabling
        if (returnToOriginalSlot.get() && originalSlot != -1 && mc.player != null) {
            mc.player.getInventory().selectedSlot = originalSlot;
        }
    }

    private void generateNewDelay() {
        double variation = delayVariation.get();
        currentDelay = delay.get() + (random.nextDouble() * 2 - 1) * variation;
        currentDelay = Math.max(0.5, currentDelay); // Ensure minimum delay
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        ClientPlayerEntity player = mc.player;
        long currentTime = System.currentTimeMillis();

        // Check if player is gliding
        if (!player.isGliding()) {
            // Return to original slot when stopping flight
            if (returnToOriginalSlot.get() && originalSlot != -1 && currentRocketSlot != -1) {
                player.getInventory().selectedSlot = originalSlot;
                originalSlot = -1;
            }

            lowDurabilityNotified = false;
            criticalDurabilityNotified = false;
            outOfRocketsNotified = false;
            currentRocketSlot = -1;
            return;
        }

        // Check elytra durability
        ItemStack chestItem = player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chestItem.isEmpty() && chestItem.getItem() == Items.ELYTRA && chestItem.isDamageable()) {
            int currentDurability = chestItem.getMaxDamage() - chestItem.getDamage();
            int maxDurability = chestItem.getMaxDamage();
            double durabilityPercentage = (double) currentDurability / maxDurability * 100;

            // Show notification if durability is at 20% or less
            if (durabilityNotification.get() && durabilityPercentage <= 20 && !lowDurabilityNotified) {
                mc.getToastManager().add(new MeteorToast(
                    Items.ELYTRA,
                    title,
                    String.format("Elytra durability low! (%.1f%%)", durabilityPercentage)
                ));
                lowDurabilityNotified = true;
            }

            // Show critical notification when about to break (at or below minDurability)
            if (durabilityNotification.get() && currentDurability <= minDurability.get() && !criticalDurabilityNotified) {
                // Double notification for critical durability
                mc.getToastManager().add(new MeteorToast(
                    Items.ELYTRA,
                    title,
                    "⚠ ELYTRA ABOUT TO BREAK! ⚠"
                ));
                mc.getToastManager().add(new MeteorToast(
                    Items.ELYTRA,
                    title,
                    String.format("Only %d durability left!", currentDurability)
                ));
                criticalDurabilityNotified = true;
            }

            // Stop using fireworks if durability check is enabled and below threshold
            if (checkDurability.get() && currentDurability <= minDurability.get()) {
                return;
            }
        }

        // Check if enough time has passed since last firework
        if ((currentTime - lastFireworkTime) < (currentDelay * 1000.0)) {
            return;
        }

        // Check if current slot still has rockets
        if (currentRocketSlot != -1 && currentRocketSlot < 9) {
            ItemStack stack = player.getInventory().getStack(currentRocketSlot);
            if (!stack.isEmpty() && stack.getItem() instanceof FireworkRocketItem) {
                // Still have rockets in current slot, use them
                useRocket(player);
                lastFireworkTime = currentTime;
                generateNewDelay();
                return;
            } else {
                // Current slot is empty, need to find new rockets
                currentRocketSlot = -1;
            }
        }

        // Find a new rocket slot
        int rocketSlot = findRocketSlot(player);

        if (rocketSlot == -1) {
            // No rockets found
            if (outOfRocketsNotification.get() && !outOfRocketsNotified) {
                mc.getToastManager().add(new MeteorToast(
                    Items.FIREWORK_ROCKET,
                    title,
                    "Out of Rockets!"
                ));
                outOfRocketsNotified = true;
            }

            // Return to original slot if out of rockets
            if (returnToOriginalSlot.get() && originalSlot != -1) {
                player.getInventory().selectedSlot = originalSlot;
                originalSlot = -1;
                currentRocketSlot = -1;
            }
            return;
        }

        // Reset out of rockets notification if we found rockets
        outOfRocketsNotified = false;

        // Switch to the new rocket slot
        if (originalSlot == -1) {
            originalSlot = player.getInventory().selectedSlot;
        }

        currentRocketSlot = rocketSlot;
        player.getInventory().selectedSlot = rocketSlot;

        // Use the rocket after a small delay (more human-like)
        useRocket(player);

        // Update timing
        lastFireworkTime = currentTime;
        generateNewDelay();
    }

    private int findRocketSlot(ClientPlayerEntity player) {
        // Start from a random position to be less predictable
        int startSlot = random.nextInt(9);

        for (int i = 0; i < 9; i++) {
            int slot = (startSlot + i) % 9;
            ItemStack stack = player.getInventory().getStack(slot);

            if (!stack.isEmpty() && stack.getItem() instanceof FireworkRocketItem) {
                return slot;
            }
        }

        return -1;
    }

    private void useRocket(ClientPlayerEntity player) {
        // Simply use the firework - we're already on the correct slot
        mc.interactionManager.interactItem(player, Hand.MAIN_HAND);
    }
}
