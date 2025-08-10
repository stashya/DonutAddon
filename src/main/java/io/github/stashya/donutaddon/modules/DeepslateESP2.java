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

public class DeepslateESP2 extends Module {
    private final SettingGroup sgRender;
    private final Setting<SettingColor> espColor;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<Boolean> chatFeedback;
    private final Set<BlockPos> detectedDeepslate;

    public DeepslateESP2() {
        super(DonutAddon.CATEGORY, "deepslate-esp+", "ESP for all deepslate blocks at y >= 8.");

        this.sgRender = this.settings.createGroup("Render");

        this.espColor = this.sgRender.add(new ColorSetting.Builder()
            .name("esp-color")
            .description("ESP box color.")
            .defaultValue(new SettingColor(0, 200, 255, 100))
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

        this.detectedDeepslate = new HashSet<>();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        this.scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;
        boolean isDeepslate = this.isTargetDeepslate(state, pos.getY());

        if (isDeepslate && this.detectedDeepslate.add(pos)) {
            if (this.chatFeedback.get()) {
                this.info("§bDeepslateESP+§f: Deepslate at §a" + pos.toShortString());
            }
        } else if (!isDeepslate) {
            this.detectedDeepslate.remove(pos);
        }
    }

    private void scanChunk(Chunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = Math.min(128, yMin + chunk.getHeight());

        for (int x = xStart; x < xStart + 16; ++x) {
            for (int z = zStart; z < zStart + 16; ++z) {
                for (int y = Math.max(yMin, 8); y < yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (this.isTargetDeepslate(chunk.getBlockState(pos), y) && this.detectedDeepslate.add(pos) && this.chatFeedback.get()) {
                        this.info("§bDeepslateESP+§f: Deepslate at §a" + pos.toShortString());
                    }
                }
            }
        }
    }

    private boolean isTargetDeepslate(BlockState state, int y) {
        return y >= 8 && y < 128 && state.getBlock() == Blocks.DEEPSLATE;
    }

    @Override
    public void onActivate() {
        this.detectedDeepslate.clear();
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

        for (BlockPos pos : this.detectedDeepslate) {
            event.renderer.box(pos, sideColor, lineColor, this.shapeMode.get(), 0);
        }
    }
}
