/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class PillagerESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Settings
    private final Setting<Boolean> sound = sgGeneral.add(new BoolSetting.Builder()
        .name("sound")
        .description("Plays a sound when Pillagers are detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the tracer lines.")
        .defaultValue(new SettingColor(255, 0, 0, 127))
        .build()
    );

    private final Set<UUID> trackedPillagers = new HashSet<>();
    private final List<PillagerEntity> pendingNotifications = new ArrayList<>();
    private int notificationTimer = 0;

    public PillagerESP() {
        super(DonutAddon.CATEGORY, "pillager-esp", "Detects Pillagers and draws tracers to them.");
    }

    @Override
    public void onActivate() {
        trackedPillagers.clear();
        pendingNotifications.clear();
        notificationTimer = 0;

        // Check for existing pillagers
        if (mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof PillagerEntity pillager) {
                    trackedPillagers.add(pillager.getUuid());
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        trackedPillagers.clear();
        pendingNotifications.clear();
        notificationTimer = 0;
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof PillagerEntity pillager)) return;

        if (!trackedPillagers.contains(pillager.getUuid())) {
            trackedPillagers.add(pillager.getUuid());
            pendingNotifications.add(pillager);
            notificationTimer = 5; // Delay to collect multiple pillagers
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (!(event.entity instanceof PillagerEntity)) return;
        trackedPillagers.remove(event.entity.getUuid());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Handle grouped notifications
        if (notificationTimer > 0) {
            notificationTimer--;

            if (notificationTimer == 0 && !pendingNotifications.isEmpty()) {
                sendGroupedNotification();
                pendingNotifications.clear();
            }
        }

        // Clean up tracked pillagers that no longer exist
        trackedPillagers.removeIf(uuid -> {
            for (Entity entity : mc.world.getEntities()) {
                if (entity.getUuid().equals(uuid) && !entity.isRemoved()) {
                    return false;
                }
            }
            return true;
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.options.hudHidden) return;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof PillagerEntity pillager)) continue;
            if (!trackedPillagers.contains(pillager.getUuid())) continue;

            // Calculate position with interpolation
            double x = pillager.prevX + (pillager.getX() - pillager.prevX) * event.tickDelta;
            double y = pillager.prevY + (pillager.getY() - pillager.prevY) * event.tickDelta;
            double z = pillager.prevZ + (pillager.getZ() - pillager.prevZ) * event.tickDelta;

            // Target body (middle of entity)
            double height = pillager.getBoundingBox().maxY - pillager.getBoundingBox().minY;
            y += height / 2;

            // Draw tracer line
            event.renderer.line(
                RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                x, y, z,
                tracerColor.get()
            );
        }
    }

    private void sendGroupedNotification() {
        if (pendingNotifications.isEmpty()) return;

        PillagerEntity firstPillager = pendingNotifications.get(0);

        if (pendingNotifications.size() == 1) {
            ChatUtils.sendMsg(Text.literal("")
                .append(Text.literal("Pillager").formatted(Formatting.RED))
                .append(Text.literal(" found at ").formatted(Formatting.GRAY))
                .append(Text.literal(String.format("[%d, %d, %d]",
                    (int)firstPillager.getX(),
                    (int)firstPillager.getY(),
                    (int)firstPillager.getZ())).formatted(Formatting.WHITE))
            );
        } else {
            ChatUtils.sendMsg(Text.literal("")
                .append(Text.literal("Group of Pillagers").formatted(Formatting.RED))
                .append(Text.literal(" found at ").formatted(Formatting.GRAY))
                .append(Text.literal(String.format("[%d, %d, %d]",
                    (int)firstPillager.getX(),
                    (int)firstPillager.getY(),
                    (int)firstPillager.getZ())).formatted(Formatting.WHITE))
            );
        }

        if (sound.get() && mc.world != null && mc.player != null) {
            mc.world.playSoundFromEntity(
                mc.player,
                mc.player,
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.AMBIENT,
                3.0f,
                1.0f
            );
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(trackedPillagers.size());
    }
}
