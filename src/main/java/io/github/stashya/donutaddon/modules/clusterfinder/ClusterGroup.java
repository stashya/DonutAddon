/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package io.github.stashya.donutaddon.modules.clusterfinder;

import io.github.stashya.donutaddon.modules.ClusterFinder;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.UnorderedArrayList;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.block.Block;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;

public class ClusterGroup {
    private static ClusterFinder getClusterFinder() {
        return Modules.get().get(ClusterFinder.class);
    }

    private final Block block;

    public final UnorderedArrayList<ClusterBlock> blocks = new UnorderedArrayList<>();

    public double sumX, sumY, sumZ;

    public ClusterGroup(Block block) {
        this.block = block;
    }

    public void add(ClusterBlock block, boolean removeFromOld, boolean splitGroup) {
        blocks.add(block);
        sumX += block.x;
        sumY += block.y;
        sumZ += block.z;

        if (block.group != null && removeFromOld) block.group.remove(block, splitGroup);
        block.group = this;
    }

    public void add(ClusterBlock block) {
        add(block, true, true);
    }

    public void remove(ClusterBlock block, boolean splitGroup) {
        blocks.remove(block);
        sumX -= block.x;
        sumY -= block.y;
        sumZ -= block.z;

        if (blocks.isEmpty()) getClusterFinder().removeGroup(block.group);
        else if (splitGroup) {
            trySplit(block);
        }
    }

    public void remove(ClusterBlock block) {
        remove(block, true);
    }

    private void trySplit(ClusterBlock block) {
        Set<ClusterBlock> neighbours = new ObjectOpenHashSet<>(6);

        for (int side : ClusterBlock.SIDES) {
            if ((block.neighbours & side) == side) {
                ClusterBlock neighbour = block.getSideBlock(side);
                if (neighbour != null) neighbours.add(neighbour);
            }
        }
        if (neighbours.size() <= 1) return;

        Set<ClusterBlock> remainingBlocks = new ObjectOpenHashSet<>(blocks);
        Queue<ClusterBlock> blocksToCheck = new ArrayDeque<>();

        blocksToCheck.offer(blocks.getFirst());
        remainingBlocks.remove(blocks.getFirst());
        neighbours.remove(blocks.getFirst());

        loop: {
            while (!blocksToCheck.isEmpty()) {
                ClusterBlock b = blocksToCheck.poll();

                for (int side : ClusterBlock.SIDES) {
                    if ((b.neighbours & side) != side) continue;
                    ClusterBlock neighbour = b.getSideBlock(side);

                    if (neighbour != null && remainingBlocks.contains(neighbour)) {
                        blocksToCheck.offer(neighbour);
                        remainingBlocks.remove(neighbour);

                        neighbours.remove(neighbour);
                        if (neighbours.isEmpty()) break loop;
                    }
                }
            }
        }

        if (!neighbours.isEmpty()) {
            ClusterGroup group = getClusterFinder().newGroup(this.block);
            group.blocks.ensureCapacity(remainingBlocks.size());

            blocks.removeIf(remainingBlocks::contains);

            for (ClusterBlock b : remainingBlocks) {
                group.add(b, false, false);

                sumX -= b.x;
                sumY -= b.y;
                sumZ -= b.z;
            }

            if (neighbours.size() > 1) {
                block.neighbours = 0;

                for (ClusterBlock b : neighbours) {
                    int x = b.x - block.x;
                    if (x == 1) block.neighbours |= ClusterBlock.RI;
                    else if (x == -1) block.neighbours |= ClusterBlock.LE;

                    int y = b.y - block.y;
                    if (y == 1) block.neighbours |= ClusterBlock.TO;
                    else if (y == -1) block.neighbours |= ClusterBlock.BO;

                    int z = b.z - block.z;
                    if (z == 1) block.neighbours |= ClusterBlock.FO;
                    else if (z == -1) block.neighbours |= ClusterBlock.BA;
                }

                group.trySplit(block);
            }
        }
    }

    public void merge(ClusterGroup group) {
        blocks.ensureCapacity(blocks.size() + group.blocks.size());
        for (ClusterBlock block : group.blocks) add(block, false, false);
        getClusterFinder().removeGroup(group);
    }

    public void render(Render3DEvent event) {
        ClusterBlockData blockData = getClusterFinder().getBlockData(block);

        if (blockData.tracer) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, sumX / blocks.size() + 0.5, sumY / blocks.size() + 0.5, sumZ / blocks.size() + 0.5, blockData.tracerColor);
        }
    }
}
