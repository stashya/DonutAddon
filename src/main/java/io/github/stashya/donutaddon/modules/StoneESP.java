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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.Set;

public class StoneESP extends Module {
    private final SettingGroup sgRender;
    private final Setting<SettingColor> espColor;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<Boolean> chatFeedback;
    private final Set<BlockPos> detectedStone;

    public StoneESP() {
        super(DonutAddon.CATEGORY, "stone-esp", "ESP for stone blocks below deepslate level (some false positives with lava generated stone");

        this.sgRender = this.settings.createGroup("Render");

        this.espColor = this.sgRender.add(new ColorSetting.Builder()
            .name("esp-color")
            .description("ESP box color.")
            .defaultValue(new SettingColor(120, 120, 120, 100))
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

        this.detectedStone = new HashSet<>();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        this.scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;
        boolean isStone = this.isTargetStone(state, pos.getY());

        if (isStone && this.detectedStone.add(pos)) {
            if (this.chatFeedback.get()) {
                this.info("§7StoneESP§f: Stone at §a" + pos.toShortString());
            }
        } else if (!isStone) {
            this.detectedStone.remove(pos);
        }
    }

    private void scanChunk(Chunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = Math.min(1, yMin + chunk.getHeight());

        for (int x = xStart; x < xStart + 16; ++x) {
            for (int z = zStart; z < zStart + 16; ++z) {
                for (int y = yMin; y < yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (this.isTargetStone(chunk.getBlockState(pos), y) && this.detectedStone.add(pos) && this.chatFeedback.get()) {
                        this.info("§7StoneESP§f: Stone at §a" + pos.toShortString());
                    }
                }
            }
        }
    }

    private boolean isTargetStone(BlockState state, int y) {
        return y <= -1 && state.getBlock() == Blocks.STONE;
    }

    @Override
    public void onActivate() {
        this.detectedStone.clear();
        if (this.mc.world != null) {
            for (Chunk chunk : Utils.chunks()) {
                this.scanChunk(chunk);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        Color sideColor = new Color(this.espColor.get());
        Color lineColor = new Color(this.espColor.get());

        for (BlockPos pos : this.detectedStone) {
            event.renderer.box(pos, sideColor, lineColor, this.shapeMode.get(), 0);
        }
    }
}
