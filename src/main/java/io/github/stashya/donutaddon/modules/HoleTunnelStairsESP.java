//made by etianl with "inspiration" from Meteor Client.
//Thank you to them for a bit of this code https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/systems/modules/render/TunnelESP.java

package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HoleTunnelStairsESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHParams = settings.createGroup("Hole Parameters");
    private final SettingGroup sgTParams = settings.createGroup("Tunnel Parameters");
    private final SettingGroup sgSParams = settings.createGroup("Stairs Parameters");
    private final SettingGroup sgRender = settings.createGroup("Rendering");
    private final Setting<DetectionMode> detectionMode = sgGeneral.add(new EnumSetting.Builder<DetectionMode>()
        .name("Detection Mode")
        .description("Choose what to detect: holes, tunnels, stairs, or all.")
        .defaultValue(DetectionMode.ALL)
        .build()
    );
    private final Setting<Integer> maxChunks = sgGeneral.add(new IntSetting.Builder()
        .name("Chunks to process/tick")
        .description("Amount of Chunks to process per tick")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 100)
        .build()
    );
    private final Setting<Boolean> airBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("Detect only Air blocks as passable.")
        .description("Only marks tunnels or holes if their blocks are air as oppose to if the blocks are passable.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("Detection Y Minimum OffSet")
        .description("Scans blocks above or at this this many blocks from minimum build limit.")
        .min(0)
        .sliderRange(0,319)
        .defaultValue(0)
        .build()
    );
    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("Detection Y Maximum OffSet")
        .description("Scans blocks below or at this this many blocks from maximum build limit.")
        .min(0)
        .sliderRange(0,319)
        .defaultValue(0)
        .build()
    );
    private final Setting<Integer> minHoleDepth = sgHParams.add(new IntSetting.Builder()
        .name("Min Hole Depth")
        .description("Minimum depth for a hole to be detected")
        .defaultValue(4)
        .min(1)
        .sliderMax(20)
        .build()
    );
    private final Setting<Integer> minTunnelLength = sgTParams.add(new IntSetting.Builder()
        .name("Min Tunnel Length")
        .description("Minimum length for a tunnel to be detected")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .build()
    );
    private final Setting<Integer> minTunnelHeight = sgTParams.add(new IntSetting.Builder()
        .name("Min Tunnel Height")
        .description("Minimum height of the tunnels to be detected")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxTunnelHeight = sgTParams.add(new IntSetting.Builder()
        .name("Max Tunnel Height")
        .description("Maximum height of the tunnels to be detected")
        .defaultValue(3)
        .min(2)
        .sliderMax(10)
        .build()
    );
    private final Setting<Boolean> detect3x3Tunnels = sgTParams.add(new BoolSetting.Builder()
        .name("Detect 3x3 Tunnels")
        .description("Also detect 3x3 tunnels in addition to 1-wide tunnels")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> tunnel3x3Width = sgTParams.add(new IntSetting.Builder()
        .name("3x3 Tunnel Width")
        .description("Width of 3x3 tunnels to detect")
        .defaultValue(3)
        .min(3)
        .max(5)
        .sliderRange(3, 5)
        .visible(detect3x3Tunnels::get)
        .build()
    );
    private final Setting<Integer> tunnel3x3Height = sgTParams.add(new IntSetting.Builder()
        .name("3x3 Tunnel Height")
        .description("Height of 3x3 tunnels to detect")
        .defaultValue(3)
        .min(3)
        .max(5)
        .sliderRange(3, 5)
        .visible(detect3x3Tunnels::get)
        .build()
    );
    private final Setting<Integer> min3x3TunnelLength = sgTParams.add(new IntSetting.Builder()
        .name("Min 3x3 Tunnel Length")
        .description("Minimum length for 3x3 tunnels to be detected")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .visible(detect3x3Tunnels::get)
        .build()
    );
    private final Setting<Boolean> diagonals = sgTParams.add(new BoolSetting.Builder()
        .name("Detect Diagonal Tunnels.")
        .description("Detects diagonal tunnels when tunnels are selected to be detected.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> minDiagonalLength = sgTParams.add(new IntSetting.Builder()
        .name("Min Diagonal Tunnel Length")
        .description("Minimum length for diagonal tunnels to be detected")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .visible(diagonals::get)
        .build()
    );
    private final Setting<Integer> minDiagonalWidth = sgTParams.add(new IntSetting.Builder()
        .name("Min Diagonal Tunnel Width")
        .description("Minimum width for diagonal tunnels to be detected")
        .defaultValue(2)
        .min(2)
        .sliderMax(10)
        .visible(diagonals::get)
        .build()
    );
    private final Setting<Integer> maxDiagonalWidth = sgTParams.add(new IntSetting.Builder()
        .name("Max Diagonal Tunnel Width")
        .description("Maximum width for diagonal tunnels to be detected")
        .defaultValue(4)
        .min(2)
        .sliderMax(10)
        .visible(diagonals::get)
        .build()
    );
    private final Setting<Integer> minStaircaseLength = sgSParams.add(new IntSetting.Builder()
        .name("Min Staircase Length")
        .description("Minimum length for a staircase to be detected")
        .defaultValue(3)
        .min(1)
        .sliderMax(20)
        .build()
    );
    private final Setting<Integer> minStaircaseHeight = sgSParams.add(new IntSetting.Builder()
        .name("Min Staircase Height")
        .description("Minimum height of the staircase to be detected")
        .defaultValue(3)
        .min(2)
        .sliderMax(10)
        .build()
    );
    private final Setting<Integer> maxStaircaseHeight = sgSParams.add(new IntSetting.Builder()
        .name("Max Staircase Height")
        .description("Maximum height of the staircase to be detected")
        .defaultValue(5)
        .min(2)
        .sliderMax(10)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );
    private final Setting<SettingColor> holeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("hole-line-color")
        .description("The color of the lines for the holes being rendered.")
        .defaultValue(new SettingColor(255, 0, 0, 95))
        .build()
    );
    private final Setting<SettingColor> holeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("hole-side-color")
        .description("The color of the sides for the holes being rendered.")
        .defaultValue(new SettingColor(255, 0, 0, 30))
        .build()
    );
    private final Setting<SettingColor> tunnelLineColor = sgRender.add(new ColorSetting.Builder()
        .name("tunnel-line-color")
        .description("The color of the lines for the tunnels being rendered.")
        .defaultValue(new SettingColor(0, 0, 255, 95))
        .build()
    );
    private final Setting<SettingColor> tunnelSideColor = sgRender.add(new ColorSetting.Builder()
        .name("tunnel-side-color")
        .description("The color of the sides for the tunnels being rendered.")
        .defaultValue(new SettingColor(0, 0, 255, 30))
        .build()
    );
    private final Setting<SettingColor> tunnel3x3LineColor = sgRender.add(new ColorSetting.Builder()
        .name("3x3-tunnel-line-color")
        .description("The color of the lines for the 3x3 tunnels being rendered.")
        .defaultValue(new SettingColor(0, 255, 255, 95))
        .visible(detect3x3Tunnels::get)
        .build()
    );
    private final Setting<SettingColor> tunnel3x3SideColor = sgRender.add(new ColorSetting.Builder()
        .name("3x3-tunnel-side-color")
        .description("The color of the sides for the 3x3 tunnels being rendered.")
        .defaultValue(new SettingColor(0, 255, 255, 30))
        .visible(detect3x3Tunnels::get)
        .build()
    );
    private final Setting<SettingColor> staircaseLineColor = sgRender.add(new ColorSetting.Builder()
        .name("staircase-line-color")
        .description("The color of the lines for the staircases being rendered.")
        .defaultValue(new SettingColor(255, 0, 255, 95))
        .build()
    );
    private final Setting<SettingColor> staircaseSideColor = sgRender.add(new ColorSetting.Builder()
        .name("staircase-side-color")
        .description("The color of the sides for the staircases being rendered.")
        .defaultValue(new SettingColor(255, 0, 255, 30))
        .build()
    );

    private static final Direction[] DIRECTIONS = { Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH };
    private final Long2ObjectMap<TChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final Queue<Chunk> chunkQueue = new LinkedList<>();
    private final Set<Box> holes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> tunnels = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> tunnels3x3 = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Box> staircases = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public HoleTunnelStairsESP() {
        super(DonutAddon.CATEGORY, "Hole/Tunnel/StairsESP", "Finds and highlights holes and tunnels and stairs.");
    }
    // Add this method to HoleAndTunnelAndStairsESP class
    public Set<Box> getHoles() {
        return new HashSet<>(holes);
    }
    @Override
    public void onDeactivate() {
        chunks.clear();
        chunkQueue.clear();
        holes.clear();
        tunnels.clear();
        tunnels3x3.clear();
        staircases.clear();
    }
    @EventHandler
    private void onTick(TickEvent.Post event) {
        synchronized (chunks) {
            for (TChunk tChunk : chunks.values()) tChunk.marked = false;

            for (Chunk chunk : Utils.chunks(true)) {
                long key = ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z);

                if (chunks.containsKey(key)) chunks.get(key).marked = true;
                else if (!chunkQueue.contains(chunk)) {
                    chunkQueue.add(chunk);
                }
            }

            processChunkQueue();
            chunks.values().removeIf(tChunk -> !tChunk.marked);
        }
        removeBoxesOutsideRenderDistance();
    }

    private void removeBoxesOutsideRenderDistance() {
        // Collect all loaded chunks using Utils.chunks()
        Set<WorldChunk> chunkSet = new HashSet<>();
        for (Chunk chunk : Utils.chunks(true)) {
            if (chunk instanceof WorldChunk) {
                chunkSet.add((WorldChunk) chunk);
            }
        }

        removeBoxesOutsideRenderDistance(holes, chunkSet);
        removeBoxesOutsideRenderDistance(tunnels, chunkSet);
        removeBoxesOutsideRenderDistance(tunnels3x3, chunkSet);
        removeBoxesOutsideRenderDistance(staircases, chunkSet);
    }
    private void removeBoxesOutsideRenderDistance(Set<Box> boxSet, Set<WorldChunk> worldChunks) {
        boxSet.removeIf(box -> {
            BlockPos boxPos = new BlockPos((int)Math.floor(box.getCenter().getX()), (int)Math.floor(box.getCenter().getY()), (int)Math.floor(box.getCenter().getZ()));
            assert mc.world != null;
            return !worldChunks.contains(mc.world.getChunk(boxPos));
        });
    }
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        switch (detectionMode.get()) {
            case ALL:
                renderHoles(event.renderer);
                renderTunnels(event.renderer);
                render3x3Tunnels(event.renderer);
                renderStaircases(event.renderer);
                break;
            case HOLES_AND_TUNNELS:
                renderHoles(event.renderer);
                renderTunnels(event.renderer);
                render3x3Tunnels(event.renderer);
                break;
            case HOLES_AND_STAIRCASES:
                renderHoles(event.renderer);
                renderStaircases(event.renderer);
                break;
            case TUNNELS_AND_STAIRCASES:
                renderTunnels(event.renderer);
                render3x3Tunnels(event.renderer);
                renderStaircases(event.renderer);
                break;
            case HOLES:
                renderHoles(event.renderer);
                break;
            case TUNNELS:
                renderTunnels(event.renderer);
                render3x3Tunnels(event.renderer);
                break;
            case STAIRCASES:
                renderStaircases(event.renderer);
                break;
        }
    }
    private void renderHoles(Renderer3D renderer) {
        if (holes != null) {
            for (Box box : holes) {
                renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, holeSideColor.get(), holeLineColor.get(), shapeMode.get(), 0);
            }
        }
    }
    private void renderTunnels(Renderer3D renderer) {
        if (tunnels != null) {
            for (Box box : tunnels) {
                renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, tunnelSideColor.get(), tunnelLineColor.get(), shapeMode.get(), 0);
            }
        }
    }
    private void render3x3Tunnels(Renderer3D renderer) {
        if (detect3x3Tunnels.get() && tunnels3x3 != null) {
            for (Box box : tunnels3x3) {
                renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, tunnel3x3SideColor.get(), tunnel3x3LineColor.get(), shapeMode.get(), 0);
            }
        }
    }
    private void renderStaircases(Renderer3D renderer) {
        if (staircases != null) {
            for (Box box : staircases) {
                renderer.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, staircaseSideColor.get(), staircaseLineColor.get(), shapeMode.get(), 0);
            }
        }
    }
    private void processChunkQueue() {
        int maxChunksPerTick = maxChunks.get();
        int processed = 0;

        while (!chunkQueue.isEmpty() && processed < maxChunksPerTick) {
            Chunk chunk = chunkQueue.poll();
            if (chunk != null) {
                TChunk tChunk = new TChunk(chunk.getPos().x, chunk.getPos().z);
                chunks.put(tChunk.getKey(), tChunk);

                MeteorExecutor.execute(() -> searchChunk(chunk, tChunk));
                processed++;
            }
        }
    }
    private void searchChunk(Chunk chunk, TChunk tChunk) {
        var sections = chunk.getSectionArray();
        int Ymin = mc.world.getBottomY() + minY.get();
        int Ymax = mc.world.getTopYInclusive() - maxY.get();
        int Y = mc.world.getBottomY();
        for (ChunkSection section : sections) {
            if (section != null && !section.isEmpty()) {
                for (int z = 0; z <= 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            int currentY = Y + y;
                            if (currentY <= Ymin || currentY >= Ymax) continue;
                            BlockPos pos = chunk.getPos().getBlockPos(x, currentY, z);
                            if (isPassableBlock(pos)) {
                                switch (detectionMode.get()) {
                                    case ALL:
                                        checkHole(pos, holes);
                                        checkTunnel(pos);
                                        if (diagonals.get()) checkDiagonalTunnel(pos);
                                        if (detect3x3Tunnels.get()) check3x3Tunnel(pos);
                                        checkStaircase(pos);
                                        break;
                                    case HOLES_AND_TUNNELS:
                                        checkHole(pos, holes);
                                        checkTunnel(pos);
                                        if (diagonals.get()) checkDiagonalTunnel(pos);
                                        if (detect3x3Tunnels.get()) check3x3Tunnel(pos);
                                        break;
                                    case HOLES_AND_STAIRCASES:
                                        checkHole(pos, holes);
                                        checkStaircase(pos);
                                        break;
                                    case TUNNELS_AND_STAIRCASES:
                                        checkTunnel(pos);
                                        if (diagonals.get()) checkDiagonalTunnel(pos);
                                        if (detect3x3Tunnels.get()) check3x3Tunnel(pos);
                                        checkStaircase(pos);
                                        break;
                                    case HOLES:
                                        checkHole(pos, holes);
                                        break;
                                    case TUNNELS:
                                        checkTunnel(pos);
                                        if (diagonals.get()) checkDiagonalTunnel(pos);
                                        if (detect3x3Tunnels.get()) check3x3Tunnel(pos);
                                        break;
                                    case STAIRCASES:
                                        checkStaircase(pos);
                                        break;
                                }
                            }
                        }
                    }
                }
            }
            Y += 16;
        }
    }
    private void checkHole(BlockPos pos, Set<Box> holes) {
        if (isValidHoleSection(pos)) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            while (isValidHoleSection(currentPos)) {
                currentPos.move(Direction.UP);
            }
            if (currentPos.getY()-pos.getY() >= minHoleDepth.get()) {
                Box holeBox = new Box(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, currentPos.getY(), pos.getZ() + 1
                );
                if (!holes.contains(holeBox) && holes.stream().noneMatch(existingHole -> existingHole.intersects(holeBox))) {
                    holes.add(holeBox);
                }
            }
        }
    }
    private boolean isValidHoleSection(BlockPos pos) {
        return isPassableBlock(pos) && !isPassableBlock(pos.north()) && !isPassableBlock(pos.south()) && !isPassableBlock(pos.east()) && !isPassableBlock(pos.west());
    }
    private void checkTunnel(BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            int stepCount = 0;
            BlockPos startPos = null;
            BlockPos endPos = null;
            int maxHeight = 0;
            if (startPos == null && isTunnelSection(currentPos, dir)) {
                startPos = currentPos.toImmutable();
            }
            while (isTunnelSection(currentPos, dir)) {
                maxHeight = Math.max(maxHeight, getTunnelHeight(currentPos));

                endPos = currentPos.toImmutable();

                currentPos.move(dir);
                stepCount++;
            }

            if (stepCount >= minTunnelLength.get() && maxHeight >= minTunnelHeight.get() && maxHeight <= maxTunnelHeight.get()) {
                Box tunnelBox = new Box(
                    Math.min(startPos.getX(), endPos.getX()),
                    startPos.getY(),
                    Math.min(startPos.getZ(), endPos.getZ()),
                    Math.max(startPos.getX(), endPos.getX()) + 1,
                    startPos.getY() + maxHeight,
                    Math.max(startPos.getZ(), endPos.getZ()) + 1
                );

                if (!tunnels.contains(tunnelBox) && tunnels.stream().noneMatch(existingTunnel -> existingTunnel.intersects(tunnelBox))) {
                    tunnels.add(tunnelBox);
                }
            }
        }
    }
    private boolean isTunnelSection(BlockPos pos, Direction dir) {
        int height = getTunnelHeight(pos);
        if (height < minTunnelHeight.get() || height > maxTunnelHeight.get()) return false;
        if (isPassableBlock(pos.down()) || isPassableBlock(pos.up(height))) return false;
        Direction[] perpDirs = dir.getAxis() == Direction.Axis.X ? new Direction[]{Direction.NORTH, Direction.SOUTH} : new Direction[]{Direction.EAST, Direction.WEST};
        for (Direction perpDir : perpDirs) {
            for (int i = 0; i < height; i++) {
                if (isPassableBlock(pos.up(i).offset(perpDir))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void check3x3Tunnel(BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            // Check if this could be the start of a 3x3 tunnel
            if (isValid3x3TunnelStart(pos, dir)) {
                BlockPos.Mutable currentPos = pos.mutableCopy();
                int stepCount = 0;
                BlockPos startPos = pos;
                BlockPos endPos = pos;

                // Move along the tunnel
                while (is3x3TunnelSection(currentPos, dir)) {
                    endPos = currentPos.toImmutable();
                    currentPos.move(dir);
                    stepCount++;
                }

                if (stepCount >= min3x3TunnelLength.get()) {
                    // Create box for the entire 3x3 tunnel
                    Box tunnelBox;
                    int halfWidth = tunnel3x3Width.get() / 2;
                    if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                        tunnelBox = new Box(
                            startPos.getX() - halfWidth,
                            startPos.getY(),
                            Math.min(startPos.getZ(), endPos.getZ()),
                            startPos.getX() + halfWidth + 1,
                            startPos.getY() + tunnel3x3Height.get(),
                            Math.max(startPos.getZ(), endPos.getZ()) + 1
                        );
                    } else {
                        tunnelBox = new Box(
                            Math.min(startPos.getX(), endPos.getX()),
                            startPos.getY(),
                            startPos.getZ() - halfWidth,
                            Math.max(startPos.getX(), endPos.getX()) + 1,
                            startPos.getY() + tunnel3x3Height.get(),
                            startPos.getZ() + halfWidth + 1
                        );
                    }

                    if (!tunnels3x3.contains(tunnelBox) && tunnels3x3.stream().noneMatch(existingTunnel -> existingTunnel.intersects(tunnelBox))) {
                        tunnels3x3.add(tunnelBox);
                    }
                }
            }
        }
    }

    private boolean isValid3x3TunnelStart(BlockPos pos, Direction dir) {
        // Check if this position could be the center of a 3x3 tunnel cross-section
        int width = tunnel3x3Width.get();
        int height = tunnel3x3Height.get();
        int halfWidth = width / 2;

        // Check floor and ceiling
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                    // Check floor
                    if (isPassableBlock(pos.add(dx, -1, 0))) return false;
                    // Check ceiling
                    if (isPassableBlock(pos.add(dx, height, 0))) return false;
                } else {
                    // Check floor
                    if (isPassableBlock(pos.add(0, -1, dz))) return false;
                    // Check ceiling
                    if (isPassableBlock(pos.add(0, height, dz))) return false;
                }
            }
        }

        return true;
    }

    private boolean is3x3TunnelSection(BlockPos pos, Direction dir) {
        int width = tunnel3x3Width.get();
        int height = tunnel3x3Height.get();
        int halfWidth = width / 2;

        // Check if the 3x3 area is passable
        for (int h = 0; h < height; h++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos checkPos;
                if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                    checkPos = pos.add(w, h, 0);
                } else {
                    checkPos = pos.add(0, h, w);
                }
                if (!isPassableBlock(checkPos)) return false;
            }
        }

        // Check walls (sides perpendicular to direction)
        for (int h = 0; h < height; h++) {
            if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                // Check east wall
                if (isPassableBlock(pos.add(halfWidth + 1, h, 0))) return false;
                // Check west wall
                if (isPassableBlock(pos.add(-halfWidth - 1, h, 0))) return false;
            } else {
                // Check north wall
                if (isPassableBlock(pos.add(0, h, -halfWidth - 1))) return false;
                // Check south wall
                if (isPassableBlock(pos.add(0, h, halfWidth + 1))) return false;
            }
        }

        // Check floor and ceiling
        for (int w = -halfWidth; w <= halfWidth; w++) {
            BlockPos floorPos, ceilingPos;
            if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                floorPos = pos.add(w, -1, 0);
                ceilingPos = pos.add(w, height, 0);
            } else {
                floorPos = pos.add(0, -1, w);
                ceilingPos = pos.add(0, height, w);
            }
            if (isPassableBlock(floorPos) || isPassableBlock(ceilingPos)) return false;
        }

        return true;
    }

    private void checkDiagonalTunnel(BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            for (int i = minDiagonalWidth.get()-1; i < maxDiagonalWidth.get(); i++) {
                BlockPos.Mutable currentPos = pos.mutableCopy();
                int stepCount = 0;
                List<Box> potentialBoxes = new ArrayList<>();

                Direction checkingDir = dir;
                boolean turnRight = true;

                while (isDiagonalTunnelSection(currentPos, checkingDir)) {
                    int height = getTunnelHeight(currentPos);
                    Box tunnelBox = new Box(
                        currentPos.getX(),
                        currentPos.getY(),
                        currentPos.getZ(),
                        currentPos.getX() + 1,
                        currentPos.getY() + height,
                        currentPos.getZ() + 1
                    );
                    if (!potentialBoxes.contains(tunnelBox) && !potentialBoxes.stream().anyMatch(existingDiagonal -> existingDiagonal.intersects(tunnelBox))) {
                        potentialBoxes.add(tunnelBox);
                    }

                    if (turnRight) {
                        checkingDir = checkingDir.rotateYClockwise();
                        currentPos.move(checkingDir.rotateYClockwise(), i);
                        turnRight = false;
                    } else {
                        checkingDir = checkingDir.rotateYCounterclockwise();
                        currentPos.move(checkingDir.rotateYCounterclockwise(), i);
                        turnRight = true;
                    }
                    stepCount++;
                }

                if (stepCount/minDiagonalWidth.get() >= minDiagonalLength.get()) {
                    potentialBoxes.forEach(potentialBox -> {
                        if (!tunnels.contains(potentialBox) && tunnels.stream().noneMatch(existingDiagonal -> existingDiagonal.intersects(potentialBox))) {
                            tunnels.add(potentialBox);
                        }
                    });
                }
            }
        }
    }

    private boolean isDiagonalTunnelSection(BlockPos pos, Direction dir) {
        int height = getTunnelHeight(pos);
        if (height < minTunnelHeight.get() || height > maxTunnelHeight.get()) return false;
        if (isPassableBlock(pos.down()) || isPassableBlock(pos.up(height))) return false;

        boolean waspassableblockfound = false;
        for (int i = 0; i < height; i++) {
            if (isPassableBlock(pos.up(i).offset(dir))) waspassableblockfound = true;
        }
        if (waspassableblockfound) return false;

        return true;
    }

    private int getTunnelHeight(BlockPos pos) {
        int height = 0;
        while (isPassableBlock(pos.up(height)) && height < maxTunnelHeight.get()) {
            height++;
        }
        return height;
    }
    private void checkStaircase(BlockPos pos) {
        for (Direction dir : DIRECTIONS) {
            BlockPos.Mutable currentPos = pos.mutableCopy();
            int stepCount = 0;
            List<Box> potentialStaircaseBoxes = new ArrayList<>();

            while (isStaircaseSection(currentPos, dir)) {
                int height = getStaircaseHeight(currentPos);
                Box stairsBox = new Box(
                    currentPos.getX(),
                    currentPos.getY(),
                    currentPos.getZ(),
                    currentPos.getX() + 1,
                    currentPos.getY() + height,
                    currentPos.getZ() + 1
                );
                if (!potentialStaircaseBoxes.contains(stairsBox) && !potentialStaircaseBoxes.stream().anyMatch(existingStaircase -> existingStaircase.intersects(stairsBox))) {
                    potentialStaircaseBoxes.add(stairsBox);
                }
                currentPos.move(dir);
                currentPos.move(Direction.UP);
                stepCount++;
            }

            for (Box stairsBox : potentialStaircaseBoxes){
                if (stepCount >= minStaircaseLength.get() && !staircases.contains(stairsBox) && !staircases.stream().anyMatch(existingStaircase -> existingStaircase.intersects(stairsBox))) {
                    staircases.add(stairsBox);
                }
            }
        }
    }
    private int getStaircaseHeight(BlockPos pos) {
        int height = 0;
        while (isPassableBlock(pos.up(height)) && height < maxStaircaseHeight.get()) {
            height++;
        }
        return height;
    }
    private boolean isStaircaseSection(BlockPos pos, Direction dir) {
        int height = getStaircaseHeight(pos);
        if (height < minStaircaseHeight.get() || height > maxStaircaseHeight.get()) return false;
        if (isPassableBlock(pos.down()) || isPassableBlock(pos.up(height))) return false;
        Direction[] perpDirs = dir.getAxis() == Direction.Axis.X ? new Direction[]{Direction.NORTH, Direction.SOUTH} : new Direction[]{Direction.EAST, Direction.WEST};
        for (Direction perpDir : perpDirs) {
            for (int i = 0; i < height; i++) {
                if (isPassableBlock(pos.up(i).offset(perpDir))) {
                    return false;
                }
            }
        }
        return true;
    }
    private boolean isPassableBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (airBlocks.get()) {
            return state.isAir();
        } else {
            VoxelShape shape = state.getCollisionShape(mc.world, pos);
            return shape.isEmpty() || !VoxelShapes.fullCube().equals(shape);
        }
    }

    public enum DetectionMode {
        ALL,
        HOLES_AND_TUNNELS,
        HOLES_AND_STAIRCASES,
        TUNNELS_AND_STAIRCASES,
        HOLES,
        TUNNELS,
        STAIRCASES
    }

    private class TChunk {
        private final int x, z;
        public boolean marked;

        public TChunk(int x, int z) {
            this.x = x;
            this.z = z;
            this.marked = true;
        }
        public long getKey() {
            return ChunkPos.toLong(x, z);
        }
    }
}
