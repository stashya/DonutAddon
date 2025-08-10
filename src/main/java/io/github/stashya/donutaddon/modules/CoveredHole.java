package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CoveredHole extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> chatNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Notifications")
        .description("Send chat messages when covered holes are found")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyPlayerCovered = sgGeneral.add(new BoolSetting.Builder()
        .name("Only Player-Covered")
        .description("Only detect holes that appear to be intentionally covered (not random generation)")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("Shape Mode")
        .description("How the shapes are rendered")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("Line Color")
        .description("The color of the lines for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 255)) // Orange
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("Side Color")
        .description("The color of the sides for covered holes")
        .defaultValue(new SettingColor(255, 165, 0, 50)) // Orange with transparency
        .build()
    );

    private final Map<Box, CoveredHoleInfo> coveredHoles = new ConcurrentHashMap<>();
    private final Set<Box> processedHoles = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private HoleTunnelStairsESP holeESP;
    private int tickCounter = 0;

    public CoveredHole() {
        super(DonutAddon.CATEGORY, "covered-hole", "Detects covered holes found by Hole/Tunnel/StairsESP. Requires HoleESP to be enabled.");
    }

    @Override
    public void onActivate() {
        // Check if HoleAndTunnelAndStairsESP is available and active
        holeESP = Modules.get().get(HoleTunnelStairsESP.class);

        if (holeESP == null || !holeESP.isActive()) {
            error("HoleTunnelStairsESP must be active for CoveredHole to work!");
            toggle();
            return;
        }

        coveredHoles.clear();
        processedHoles.clear();
        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        coveredHoles.clear();
        processedHoles.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Check if HoleESP is still active
        if (holeESP == null || !holeESP.isActive()) {
            error("HoleTunnelStairsESP was disabled!");
            toggle();
            return;
        }

        tickCounter++;

        // Only check every 10 ticks to reduce lag
        if (tickCounter % 10 != 0) return;

        // Get holes from HoleESP using reflection or make the field public in HoleESP
        Set<Box> holes = getHolesFromHoleESP();

        if (holes == null || holes.isEmpty()) return;

        // Check each hole to see if it's covered
        for (Box hole : holes) {
            if (!processedHoles.contains(hole)) {
                checkIfHoleCovered(hole);
                processedHoles.add(hole);
            }
        }

        // Remove covered holes that are no longer in the HoleESP list
        coveredHoles.entrySet().removeIf(entry -> !holes.contains(entry.getKey()));
        processedHoles.removeIf(hole -> !holes.contains(hole));
    }

    private Set<Box> getHolesFromHoleESP() {
        // Use the public getter method
        return holeESP.getHoles();
    }

    private void checkIfHoleCovered(Box hole) {
        // Get the top position of the hole
        BlockPos topPos = new BlockPos(
            (int) hole.minX,
            (int) hole.maxY, // Top of the hole
            (int) hole.minZ
        );

        // Check if there's a solid block directly above the hole (the "lid")
        if (isSolidBlock(topPos)) {
            boolean isPlayerCovered = true;

            if (onlyPlayerCovered.get()) {
                // Check if this looks like an intentional cover or natural generation
                isPlayerCovered = isLikelyPlayerCovered(topPos, hole);
            }

            if (!onlyPlayerCovered.get() || isPlayerCovered) {
                CoveredHoleInfo info = new CoveredHoleInfo(topPos, hole);
                coveredHoles.put(hole, info);

                if (chatNotifications.get()) {
                    int depth = (int) (hole.maxY - hole.minY);
                    info("§6Covered Hole found at §a" + topPos.toShortString() + " §6(depth: " + depth + ")");
                }
            }
        }
    }

    private boolean isLikelyPlayerCovered(BlockPos coverPos, Box hole) {
        // Check if the cover block type is different from surrounding blocks
        // This suggests it was placed by a player rather than natural generation

        BlockState coverBlock = mc.world.getBlockState(coverPos);

        // Check surrounding blocks at the same Y level
        int matchingBlocks = 0;
        BlockPos[] checkPositions = {
            coverPos.north(),
            coverPos.south(),
            coverPos.east(),
            coverPos.west(),
            coverPos.north().east(),
            coverPos.north().west(),
            coverPos.south().east(),
            coverPos.south().west()
        };

        for (BlockPos pos : checkPositions) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.getBlock() == coverBlock.getBlock()) {
                matchingBlocks++;
            }
        }

        // If less than half of surrounding blocks match, it's likely player-placed
        // Also check if it's a common building block
        return matchingBlocks < 4 || isCommonBuildingBlock(coverBlock);
    }

    private boolean isCommonBuildingBlock(BlockState state) {
        String blockName = state.getBlock().getName().getString().toLowerCase();
        return blockName.contains("cobblestone") ||
            blockName.contains("stone_bricks") ||
            blockName.contains("planks") ||
            blockName.contains("logs") ||
            blockName.contains("wool") ||
            blockName.contains("concrete") ||
            blockName.contains("terracotta") ||
            blockName.contains("glass");
    }

    private boolean isSolidBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.isSolidBlock(mc.world, pos);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (Map.Entry<Box, CoveredHoleInfo> entry : coveredHoles.entrySet()) {
            Box hole = entry.getKey();
            CoveredHoleInfo info = entry.getValue();

            // Render the covered hole with a different color
            event.renderer.box(
                hole.minX, hole.minY, hole.minZ,
                hole.maxX, hole.maxY, hole.maxZ,
                sideColor.get(), lineColor.get(),
                shapeMode.get(), 0
            );

            // Optionally render the cover block highlight
            event.renderer.box(
                info.coverPos.getX(), info.coverPos.getY(), info.coverPos.getZ(),
                info.coverPos.getX() + 1, info.coverPos.getY() + 1, info.coverPos.getZ() + 1,
                sideColor.get(), lineColor.get(),
                shapeMode.get(), 0
            );
        }
    }

    private static class CoveredHoleInfo {
        public final BlockPos coverPos;
        public final Box holeBox;

        public CoveredHoleInfo(BlockPos coverPos, Box holeBox) {
            this.coverPos = coverPos;
            this.holeBox = holeBox;
        }
    }
}
