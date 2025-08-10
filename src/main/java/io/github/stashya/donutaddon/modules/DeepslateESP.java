package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.Set;

public class DeepslateESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General settings
    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y-level")
        .description("Maximum Y level to detect deepslate.")
        .defaultValue(128)
        .min(-64)
        .max(319)
        .sliderMin(-64)
        .sliderMax(128)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce detections in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show a toast when rotated deepslate is detected.")
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

    // Render settings
    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("ESP box color.")
        .defaultValue(new SettingColor(255, 0, 255, 100))
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Box render mode.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Render tracer lines to rotated deepslate.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Tracer line color.")
        .defaultValue(new SettingColor(255, 0, 255, 125))
        .visible(tracers::get)
        .build()
    );

    private final Set<BlockPos> rotatedDeepslate;
    private int toastCount = 0;

    public DeepslateESP() {
        super(DonutAddon.CATEGORY, "deepslate-esp", "ESP for rotated deepslate (player placed)");
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

            if (this.notifications.get() && toastCount < this.maxToasts.get()) {
                mc.getToastManager().add(new MeteorToast(
                    Items.DEEPSLATE,
                    title,
                    "Found rotated deepslate at " + pos.toShortString()
                ));
                toastCount++;
            }
        } else if (!isRotated) {
            this.rotatedDeepslate.remove(pos);
        }
    }

    private void scanChunk(Chunk chunk) {
        int xStart = chunk.getPos().getStartX();
        int zStart = chunk.getPos().getStartZ();
        int yMin = chunk.getBottomY();
        int yMax = Math.min(this.maxY.get(), yMin + chunk.getHeight());

        int foundInChunk = 0;

        for (int x = xStart; x < xStart + 16; ++x) {
            for (int z = zStart; z < zStart + 16; ++z) {
                for (int y = yMin; y < yMax; ++y) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (this.isRotatedDeepslate(chunk.getBlockState(pos), y) && this.rotatedDeepslate.add(pos)) {
                        foundInChunk++;

                        if (this.chatFeedback.get()) {
                            this.info("§dDeepslateESP§f: Rotated deepslate at §a" + pos.toShortString());
                        }
                    }
                }
            }
        }

        // Show summary toast for chunk if many blocks found
        if (this.notifications.get() && foundInChunk > 0) {
            if (foundInChunk > this.maxToasts.get()) {
                mc.getToastManager().add(new MeteorToast(
                    Items.DEEPSLATE,
                    title,
                    String.format("Found %d rotated deepslate blocks in chunk (%d, %d)",
                        foundInChunk, chunk.getPos().x, chunk.getPos().z)
                ));
            } else if (toastCount < this.maxToasts.get()) {
                // Individual toasts were already shown in the loop
                toastCount += foundInChunk;
            }
        }
    }

    private boolean isRotatedDeepslate(BlockState state, int y) {
        return y < this.maxY.get() && state.getBlock() == Blocks.DEEPSLATE &&
            state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Axis.Y;
    }

    @Override
    public void onActivate() {
        this.rotatedDeepslate.clear();
        this.toastCount = 0;

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

            // Render tracers if enabled
            if (this.tracers.get()) {
                event.renderer.line(
                    RenderUtils.center.x,
                    RenderUtils.center.y,
                    RenderUtils.center.z,
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    new Color(this.tracerColor.get())
                );
            }
        }
    }

    @Override
    public String getInfoString() {
        return String.valueOf(this.rotatedDeepslate.size());
    }
}
