package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class DripstoneESP extends Module {
    private final SettingGroup sgRender;
    private final Setting<SettingColor> espColor;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<Boolean> chatFeedback;
    private final Setting<Integer> minLength;
    private final Set<BlockPos> longDripstoneTips;

    public DripstoneESP() {
        super(DonutAddon.CATEGORY, "Dripstone-Esp", "ESP for downward dripstone growth with configurable minimum length (4-8).");

        this.sgRender = this.settings.createGroup("Render");

        this.espColor = this.sgRender.add(new ColorSetting.Builder()
            .name("esp-color")
            .description("ESP box color.")
            .defaultValue(new SettingColor(100, 255, 200, 100))
            .build()
        );

        this.shapeMode = this.sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("Box render mode.")
            .defaultValue(ShapeMode.Both)
            .build()
        );

        this.chatFeedback = this.sgRender.add(new BoolSetting.Builder()
            .name("chat-feedback")
            .description("Announce detections in chat.")
            .defaultValue(true)
            .build()
        );

        this.minLength = this.sgRender.add(new IntSetting.Builder()
            .name("min-length")
            .description("Minimum length for stalactites to ESP highlight.")
            .defaultValue(8)
            .min(4)
            .max(8)
            .sliderRange(4, 8)
            .build()
        );

        this.longDripstoneTips = new HashSet<>();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        this.scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;

        if (this.isDripstoneTipDown(state)) {
            int len = this.getStalactiteLength(pos);
            if (len >= this.minLength.get()) {
                if (this.longDripstoneTips.add(pos) && this.chatFeedback.get()) {
                    this.info("Long dripstone at " + pos.toShortString() + " (length " + len + ")");
                }
            } else {
                this.longDripstoneTips.remove(pos);
            }
        } else {
            this.longDripstoneTips.remove(pos);
        }
    }

    private void scanChunk(WorldChunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = yMin + chunk.getHeight();

        for (int x = xStart; x < xStart + 16; ++x) {
            for (int z = zStart; z < zStart + 16; ++z) {
                for (int y = yMin; y < yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    if (this.isDripstoneTipDown(state)) {
                        int len = this.getStalactiteLength(pos);
                        if (len >= this.minLength.get() && this.longDripstoneTips.add(pos) && this.chatFeedback.get()) {
                            this.info("Long stalactite at " + pos.toShortString() + " (length " + len + ")");
                        }
                    }
                }
            }
        }
    }

    private boolean isDripstoneTipDown(BlockState state) {
        return state.getBlock() == Blocks.POINTED_DRIPSTONE &&
            state.get(PointedDripstoneBlock.VERTICAL_DIRECTION) == Direction.DOWN;
    }

    private int getStalactiteLength(BlockPos tip) {
        if (this.mc.world == null) {
            return 0;
        }

        int length = 0;
        BlockPos scan = tip;

        while (true) {
            BlockState state = this.mc.world.getBlockState(scan);
            if (state.getBlock() != Blocks.POINTED_DRIPSTONE ||
                state.get(PointedDripstoneBlock.VERTICAL_DIRECTION) != Direction.DOWN) {
                return length;
            }

            ++length;
            scan = scan.up();
        }
    }

    @Override
    public void onActivate() {
        this.longDripstoneTips.clear();
        if (this.mc.world != null) {
            Iterator var1 = Utils.chunks().iterator();

            while(var1.hasNext()) {
                WorldChunk chunk = (WorldChunk)var1.next();
                this.scanChunk(chunk);
            }

        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color sideColor = new Color(this.espColor.get());
        Color lineColor = new Color(this.espColor.get());

        for (BlockPos pos : this.longDripstoneTips) {
            event.renderer.box(pos, sideColor, lineColor, this.shapeMode.get(), 0);
        }
    }
}
