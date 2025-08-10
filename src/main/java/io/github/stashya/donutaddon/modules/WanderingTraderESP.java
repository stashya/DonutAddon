/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import net.minecraft.entity.passive.TraderLlamaEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.Set;

public class WanderingTraderESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General
    private final Setting<Boolean> soundNotification = sgGeneral.add(new BoolSetting.Builder()
        .name("sound-notification")
        .description("Plays a sound when a wandering trader is detected.")
        .defaultValue(true)
        .build()
    );

    // Render
    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("The color of the tracer.")
        .defaultValue(new SettingColor(0, 255, 255, 127))
        .build()
    );

    private final Set<Integer> detectedTraders = new HashSet<>();
    private final Set<Integer> detectedLlamas = new HashSet<>();
    private boolean hasNotified = false;

    public WanderingTraderESP() {
        super(DonutAddon.CATEGORY, "wandering-trader-esp", "Detects and highlights wandering traders and their llamas.");
    }

    @Override
    public void onActivate() {
        detectedTraders.clear();
        detectedLlamas.clear();
        hasNotified = false;

        // Check for existing entities
        if (mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof WanderingTraderEntity) {
                    handleTraderAdded(entity);
                } else if (entity instanceof TraderLlamaEntity) {
                    handleLlamaAdded(entity);
                }
            }
        }
    }

    @Override
    public void onDeactivate() {
        detectedTraders.clear();
        detectedLlamas.clear();
        hasNotified = false;
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (event.entity instanceof WanderingTraderEntity) {
            handleTraderAdded(event.entity);
        } else if (event.entity instanceof TraderLlamaEntity) {
            handleLlamaAdded(event.entity);
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof WanderingTraderEntity) {
            detectedTraders.remove(event.entity.getId());
            if (detectedTraders.isEmpty() && detectedLlamas.isEmpty()) {
                hasNotified = false;
            }
        } else if (event.entity instanceof TraderLlamaEntity) {
            detectedLlamas.remove(event.entity.getId());
            if (detectedTraders.isEmpty() && detectedLlamas.isEmpty()) {
                hasNotified = false;
            }
        }
    }

    private void handleTraderAdded(Entity trader) {
        detectedTraders.add(trader.getId());

        if (!hasNotified) {
            notifyTraderFound(trader);
            hasNotified = true;
        }
    }

    private void handleLlamaAdded(Entity llama) {
        detectedLlamas.add(llama.getId());

        // If we haven't notified yet and there's no trader detected, notify about the llama
        if (!hasNotified && detectedTraders.isEmpty()) {
            notifyLlamaFound(llama);
            hasNotified = true;
        }
    }

    private void notifyTraderFound(Entity trader) {
        ChatUtils.sendMsg(Text.literal("")
            .append(Text.literal("Wandering Trader").formatted(Formatting.AQUA))
            .append(Text.literal(" found at ").formatted(Formatting.GRAY))
            .append(Text.literal(String.format("[%d, %d, %d]",
                (int)trader.getX(),
                (int)trader.getY(),
                (int)trader.getZ())).formatted(Formatting.WHITE))
        );

        if (soundNotification.get() && mc.world != null && mc.player != null) {
            mc.world.playSoundFromEntity(mc.player, mc.player, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.AMBIENT, 3.0F, 1.0F);
        }
    }

    private void notifyLlamaFound(Entity llama) {
        ChatUtils.sendMsg(Text.literal("")
            .append(Text.literal("Trader Llama").formatted(Formatting.YELLOW))
            .append(Text.literal(" found at ").formatted(Formatting.GRAY))
            .append(Text.literal(String.format("[%d, %d, %d]",
                (int)llama.getX(),
                (int)llama.getY(),
                (int)llama.getZ())).formatted(Formatting.WHITE))
        );

        if (soundNotification.get() && mc.world != null && mc.player != null) {
            mc.world.playSoundFromEntity(mc.player, mc.player, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.AMBIENT, 3.0F, 1.0F);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        // Render tracers to all detected traders
        for (Integer id : detectedTraders) {
            Entity entity = mc.world.getEntityById(id);
            if (entity instanceof WanderingTraderEntity trader) {
                renderTracer(event, trader);
            }
        }

        // Render tracers to all detected llamas
        for (Integer id : detectedLlamas) {
            Entity entity = mc.world.getEntityById(id);
            if (entity instanceof TraderLlamaEntity llama) {
                renderTracer(event, llama);
            }
        }
    }

    private void renderTracer(Render3DEvent event, Entity entity) {
        double x = entity.prevX + (entity.getX() - entity.prevX) * event.tickDelta;
        double y = entity.prevY + (entity.getY() - entity.prevY) * event.tickDelta;
        double z = entity.prevZ + (entity.getZ() - entity.prevZ) * event.tickDelta;

        // Draw tracer to the center of the entity
        double height = entity.getBoundingBox().maxY - entity.getBoundingBox().minY;
        y += height / 2;

        event.renderer.line(
            RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
            x, y, z,
            tracerColor.get()
        );
    }

    @Override
    public String getInfoString() {
        int total = detectedTraders.size() + detectedLlamas.size();
        return total > 0 ? String.valueOf(total) : null;
    }
}
