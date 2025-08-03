package com.DonutAddon.addon.modules;

import com.DonutAddon.addon.DonutAddon;
import meteordevelopment.meteorclient.systems.modules.Categories;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.math.Direction.Axis;

public class DeepslateESP extends Module {
    private final SettingGroup sgRender;
    private final Setting<SettingColor> espColor;
    private final Setting<ShapeMode> shapeMode;
    private final Setting<Boolean> chatFeedback;
    private final Set<BlockPos> rotatedDeepslate;

    public DeepslateESP() {
        super(DonutAddon.CATEGORY, "deepslate-esp", "ESP for rotated deepslate (player placed)");

        this.sgRender = this.settings.createGroup("Render");

        this.espColor = this.sgRender.add(new ColorSetting.Builder()
            .name("esp-color")
            .description("ESP box color.")
            .defaultValue(new SettingColor(255, 0, 255, 100))
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

        this.rotatedDeepslate = new HashSet<>();
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        this.scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        BlockState state = event.newState;
        boolean isRotated = this.isRotatedDeepslate(state, pos.getY());

        if (isRotated && this.rotatedDeepslate.add(pos)) {
            if (this.chatFeedback.get()) {
                this.info("§dDeepslateESP§f: Rotated deepslate at §a" + pos.toShortString());
            }
        } else if (!isRotated) {
            this.rotatedDeepslate.remove(pos);
        }
    }

    private void scanChunk(Chunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = Math.min(128, yMin + chunk.getHeight());

        for (int x = xStart; x < xStart + 16; ++x) {
            for (int z = zStart; z < zStart + 16; ++z) {
                for (int y = yMin; y < yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (this.isRotatedDeepslate(chunk.getBlockState(pos), y) && this.rotatedDeepslate.add(pos) && this.chatFeedback.get()) {
                        this.info("§dDeepslateESP§f: Rotated deepslate at §a" + pos.toShortString());
                    }
                }
            }
        }
    }

    private boolean isRotatedDeepslate(BlockState state, int y) {
        return y < 128 && state.getBlock() == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Axis.Y;
    }

    @Override
    public void onActivate() {
        this.rotatedDeepslate.clear();
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

        for (BlockPos pos : this.rotatedDeepslate) {
            event.renderer.box(pos, sideColor, lineColor, this.shapeMode.get(), 0);
        }
    }
}
