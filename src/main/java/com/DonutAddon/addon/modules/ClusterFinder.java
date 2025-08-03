/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.DonutAddon.addon.modules;

import com.DonutAddon.addon.DonutAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import net.minecraft.item.Items;
import com.DonutAddon.addon.modules.clusterfinder.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ClusterFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // General

    private final Setting<ClusterBlockData> renderSettings = sgGeneral.add(new GenericSetting.Builder<ClusterBlockData>()
            .name("colors")
            .description("Configure how amethyst clusters are rendered.")
            .defaultValue(
                    new ClusterBlockData(
                            ShapeMode.Lines,
                            new SettingColor(138, 43, 226),  // Purple for amethyst
                            new SettingColor(138, 43, 226, 25),
                            true,
                            new SettingColor(138, 43, 226, 125)
                    )
            )
            .build()
    );

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
            .name("tracers")
            .description("Render tracer lines.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications")
            .description("Show a toast when an amethyst cluster is detected")
            .defaultValue(false)
            .build()
    );

    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();

    private final Long2ObjectMap<ClusterChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final Set<ClusterGroup> groups = new ReferenceOpenHashSet<>();
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();

    // Hardcoded to only search for amethyst clusters
    private final List<Block> blocks = new ArrayList<>();

    private long lastNotificationTime = 0;
    private static final int NOTIFICATION_DELAY = 5; // 5 seconds between notifications

    private Dimension lastDimension;

    public ClusterFinder() {
        super(DonutAddon.CATEGORY, "ClusterFinder", "Finds and highlights amethyst clusters.");

        // Only search for amethyst clusters
        blocks.add(Blocks.AMETHYST_CLUSTER);

        RainbowColors.register(this::onTickRainbow);
    }

    @Override
    public void onActivate() {
        synchronized (chunks) {
            chunks.clear();
            groups.clear();
        }

        for (Chunk chunk : Utils.chunks()) {
            searchChunk(chunk);
        }

        lastDimension = PlayerUtils.getDimension();
        lastNotificationTime = 0;
    }
    @Override
    public void toggle() {
        super.toggle();
        sendToggledMsg();
    }

    @Override
    public void onDeactivate() {
        synchronized (chunks) {
            chunks.clear();
            groups.clear();
        }
        lastNotificationTime = 0;
    }

    private void onTickRainbow() {
        if (!isActive()) return;
        renderSettings.get().tickRainbow();
    }

    public ClusterBlockData getBlockData(Block block) {
        return renderSettings.get();
    }

    private void updateChunk(int x, int z) {
        ClusterChunk chunk = chunks.get(ChunkPos.toLong(x, z));
        if (chunk != null) chunk.update();
    }

    private void updateBlock(int x, int y, int z) {
        ClusterChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
        if (chunk != null) chunk.update(x, y, z);
    }

    public ClusterBlock getBlock(int x, int y, int z) {
        ClusterChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
        return chunk == null ? null : chunk.get(x, y, z);
    }

    public ClusterGroup newGroup(Block block) {
        synchronized (chunks) {
            ClusterGroup group = new ClusterGroup(block);
            groups.add(group);
            return group;
        }
    }

    public void removeGroup(ClusterGroup group) {
        synchronized (chunks) {
            groups.remove(group);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        searchChunk(event.chunk());
    }

    private void searchChunk(Chunk chunk) {
        workerThread.submit(() -> {
            if (!isActive()) return;
            ClusterChunk schunk = ClusterChunk.searchChunk(chunk, blocks);

            if (schunk.size() > 0) {
                // Handle notifications with delay
                if (notifications.get()) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastNotificationTime > NOTIFICATION_DELAY * 1000L) {
                        lastNotificationTime = currentTime;

                        mc.getToastManager().add(new MeteorToast(
                                Items.AMETHYST_SHARD,
                                title,
                                "Found Amethyst Clusters!"
                        ));
                    }
                }

                synchronized (chunks) {
                    chunks.put(chunk.getPos().toLong(), schunk);
                    schunk.update();

                    updateChunk(chunk.getPos().x - 1, chunk.getPos().z);
                    updateChunk(chunk.getPos().x + 1, chunk.getPos().z);
                    updateChunk(chunk.getPos().x, chunk.getPos().z - 1);
                    updateChunk(chunk.getPos().x, chunk.getPos().z + 1);
                }
            }
        });
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        int bx = event.pos.getX();
        int by = event.pos.getY();
        int bz = event.pos.getZ();

        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        long key = ChunkPos.toLong(chunkX, chunkZ);

        boolean added = blocks.contains(event.newState.getBlock()) && !blocks.contains(event.oldState.getBlock());
        boolean removed = !added && !blocks.contains(event.newState.getBlock()) && blocks.contains(event.oldState.getBlock());

        if (added || removed) {
            workerThread.submit(() -> {
                synchronized (chunks) {
                    ClusterChunk chunk = chunks.get(key);

                    if (chunk == null) {
                        chunk = new ClusterChunk(chunkX, chunkZ);
                        if (chunk.shouldBeDeleted()) return;

                        chunks.put(key, chunk);
                    }

                    blockPos.set(bx, by, bz);

                    if (added) {
                        chunk.add(blockPos);

                        if (notifications.get()) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastNotificationTime > NOTIFICATION_DELAY * 1000L) {
                                lastNotificationTime = currentTime;

                                mc.getToastManager().add(new MeteorToast(
                                        Items.AMETHYST_SHARD,
                                        title,
                                        "Found Amethyst Cluster at " + event.pos.toShortString()
                                ));
                            }
                        }
                    }
                    else chunk.remove(blockPos);

                    for (int x = -1; x < 2; x++) {
                        for (int z = -1; z < 2; z++) {
                            for (int y = -1; y < 2; y++) {
                                if (x == 0 && y == 0 && z == 0) continue;

                                updateBlock(bx + x, by + y, bz + z);
                            }
                        }
                    }
                }
            });
        }
    }

    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        Dimension dimension = PlayerUtils.getDimension();

        if (lastDimension != dimension) onActivate();

        lastDimension = dimension;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        synchronized (chunks) {
            for (Iterator<ClusterChunk> it = chunks.values().iterator(); it.hasNext();) {
                ClusterChunk chunk = it.next();

                if (chunk.shouldBeDeleted()) {
                    workerThread.submit(() -> {
                        for (ClusterBlock block : chunk.blocks.values()) {
                            block.group.remove(block, false);
                            block.loaded = false;
                        }
                    });

                    it.remove();
                }
                else chunk.render(event);
            }

            if (tracers.get()) {
                for (ClusterGroup group : groups) {
                    group.render(event);
                }
            }
        }
    }

    @Override
    public String getInfoString() {
        return "%s groups".formatted(groups.size());
    }
}
