

package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import io.github.stashya.donutaddon.modules.clusterfinder.ClusterBlock;
import io.github.stashya.donutaddon.modules.clusterfinder.ClusterBlockData;
import io.github.stashya.donutaddon.modules.clusterfinder.ClusterChunk;
import io.github.stashya.donutaddon.modules.clusterfinder.ClusterGroup;
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
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class ClusterFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // General - Match BlockESP structure

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to search for.")
        .defaultValue(List.of(Blocks.AMETHYST_CLUSTER))
        .onChanged(blocks1 -> {
            if (isActive() && Utils.canUpdate()) onActivate();
        })
        .build()
    );

    private final Setting<ClusterBlockData> defaultBlockConfig = sgGeneral.add(new GenericSetting.Builder<ClusterBlockData>()
        .name("default-block-config")
        .description("Default block config.")
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

    private final Setting<Map<Block, ClusterBlockData>> blockConfigs = sgGeneral.add(new BlockDataSetting.Builder<ClusterBlockData>()
        .name("block-configs")
        .description("Config for each block.")
        .defaultData(defaultBlockConfig)
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
        .description("Show a toast when a matching block is detected")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> maxToasts = sgGeneral.add(new IntSetting.Builder()
        .name("max-toasts")
        .description("Maximum number of toasts to show at once.")
        .defaultValue(5)
        .min(1)
        .max(20)
        .sliderMin(1)
        .sliderMax(10)
        .visible(notifications::get)
        .build()
    );

    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();

    private final Long2ObjectMap<ClusterChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final Set<ClusterGroup> groups = new ReferenceOpenHashSet<>();
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();

    private Dimension lastDimension;

    public ClusterFinder() {
        super(DonutAddon.CATEGORY, "cluster-finder", "Finds and highlights specified blocks through walls.", "cluster-search");

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
    }

    @Override
    public void onDeactivate() {
        synchronized (chunks) {
            chunks.clear();
            groups.clear();
        }
    }

    private void onTickRainbow() {
        if (!isActive()) return;

        defaultBlockConfig.get().tickRainbow();
        for (ClusterBlockData blockData : blockConfigs.get().values()) blockData.tickRainbow();
    }

    public ClusterBlockData getBlockData(Block block) {
        ClusterBlockData blockData = blockConfigs.get().get(block);
        return blockData == null ? defaultBlockConfig.get() : blockData;
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
            ClusterChunk schunk = ClusterChunk.searchChunk(chunk, blocks.get());

            if (notifications.get() && schunk.size() > 0) {
                int toastCount = 0;
                int totalBlocks = schunk.blocks.size();

                // Show summary toast if there are many blocks
                if (totalBlocks > maxToasts.get()) {
                    // Create appropriate block type string
                    String blockType = "blocks";
                    if (blocks.get().size() == 1) {
                        Block firstBlock = blocks.get().get(0);
                        if (firstBlock == Blocks.AMETHYST_CLUSTER) {
                            blockType = "Amethyst Clusters";
                        } else {
                            blockType = new ItemStack(firstBlock.asItem()).getName().getString() + "s";
                        }
                    }

                    mc.getToastManager().add(new MeteorToast(
                        blocks.get().get(0).asItem(),
                        title,
                        String.format("Found %d %s in chunk (%d, %d)",
                            totalBlocks, blockType, chunk.getPos().x, chunk.getPos().z)
                    ));
                } else {
                    // Show individual toasts up to the limit
                    for (Long posLong : schunk.blocks.keySet()) {
                        if (toastCount >= maxToasts.get()) break;

                        // Decode the BlockPos
                        BlockPos bp = BlockPos.fromLong(posLong);

                        // Grab icon & name from the world chunk
                        Item icon = chunk.getBlockState(bp).getBlock().asItem();
                        String name = new ItemStack(icon).getName().getString();

                        // Fire the toast
                        mc.getToastManager().add(new MeteorToast(
                            icon,
                            title,
                            "Found " + name + " at " + bp
                        ));

                        toastCount++;
                    }
                }
            }

            if (schunk.size() > 0) {
                synchronized (chunks) {
                    chunks.put(chunk.getPos().toLong(), schunk);
                    schunk.update();

                    // Update neighbour chunks
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
        // Minecraft probably reuses the event.pos BlockPos instance
        int bx = event.pos.getX();
        int by = event.pos.getY();
        int bz = event.pos.getZ();

        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        long key = ChunkPos.toLong(chunkX, chunkZ);

        boolean added = blocks.get().contains(event.newState.getBlock()) && !blocks.get().contains(event.oldState.getBlock());
        boolean removed = !added && !blocks.get().contains(event.newState.getBlock()) && blocks.get().contains(event.oldState.getBlock());

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
                            String blockName = new ItemStack(event.newState.getBlock().asItem())
                                .getName().getString();

                            mc.getToastManager().add(new MeteorToast(
                                event.newState.getBlock().asItem(),
                                title,
                                String.format("Found %s at %s", blockName, event.pos)
                            ));
                        }
                    }
                    else chunk.remove(blockPos);

                    // Update neighbour blocks
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
