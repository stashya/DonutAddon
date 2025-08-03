package com.DonutAddon.addon.modules;

import com.DonutAddon.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import com.DonutAddon.addon.modules.AI.*;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AIStashFinder extends Module {
    private boolean isBaritoneAvailable() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");

    // Visible settings
    private final Setting<Integer> scanDepth = sgGeneral.add(new IntSetting.Builder()
        .name("scan-depth")
        .description("How many blocks ahead to scan for hazards.")
        .defaultValue(6)
        .range(4, 10)
        .sliderRange(4, 10)
        .build()
    );

    private final Setting<Boolean> humanLikeRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("human-like-rotation")
        .description("Add human-like imperfections to rotation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disconnectOnBaseFind = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-on-base-find")
        .description("Disconnect from server when a base is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> baseThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("base-threshold")
        .description("Minimum storage blocks to consider as a base.")
        .defaultValue(4)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    // Hidden settings with fixed values
    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Maximum Y level to operate at.")
        .defaultValue(-50)
        .range(-64, -10)
        .sliderRange(-64, -10)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> scanHeight = sgSafety.add(new IntSetting.Builder()
        .name("scan-height")
        .description("How many blocks above to scan for falling blocks.")
        .defaultValue(3)
        .range(3, 8)
        .sliderRange(3, 8)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> scanWidthFalling = sgSafety.add(new IntSetting.Builder()
        .name("scan-width-falling")
        .description("Scan width for falling blocks like gravel.")
        .defaultValue(1)
        .range(1, 4)
        .sliderRange(1, 4)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> scanWidthFluids = sgSafety.add(new IntSetting.Builder()
        .name("scan-width-fluids")
        .description("Scan width for fluids like lava and water.")
        .defaultValue(4)
        .range(2, 6)
        .sliderRange(2, 6)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> safetyMargin = sgSafety.add(new IntSetting.Builder()
        .name("safety-margin")
        .description("Blocks before hazard to stop mining.")
        .defaultValue(4)
        .range(1, 4)
        .sliderRange(1, 4)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> strictGroundCheck = sgSafety.add(new BoolSetting.Builder()
        .name("strict-ground-check")
        .description("Require solid ground for entire scan area.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> scanFrequency = sgSafety.add(new IntSetting.Builder()
        .name("scan-frequency")
        .description("How often to scan for hazards while mining (in ticks).")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 20)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> backtrackDistance = sgSafety.add(new IntSetting.Builder()
        .name("backtrack-distance")
        .description("How many blocks to go back when both sides are blocked.")
        .defaultValue(20)
        .range(10, 50)
        .sliderRange(10, 50)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> smoothRotation = sgRotation.add(new BoolSetting.Builder()
        .name("smooth-rotation")
        .description("Use smooth rotation instead of instant snapping.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Base rotation speed.")
        .defaultValue(4.5)
        .range(1, 10)
        .sliderRange(1, 10)
        .visible(() -> false)
        .build()
    );

    private final Setting<Double> rotationAcceleration = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-acceleration")
        .description("How quickly rotation speeds up and slows down.")
        .defaultValue(0.8)
        .range(0.1, 2.0)
        .sliderRange(0.1, 2.0)
        .visible(() -> false)
        .build()
    );

    private final Setting<Double> overshootChance = sgRotation.add(new DoubleSetting.Builder()
        .name("overshoot-chance")
        .description("Chance to overshoot target rotation.")
        .defaultValue(0.3)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(() -> false)
        .build()
    );

    // Module components
    private MiningState currentState = MiningState.IDLE;
    private DirectionManager directionManager;
    private PathScanner pathScanner;
    private SafetyValidator safetyValidator;
    private BacktrackManager backtrackManager;
    private RotationController rotationController;

    // Mining tracking
    private int blocksMined = 0;
    private Vec3d lastPos = Vec3d.ZERO;
    private int scanTicks = 0;

    // Current operation tracking
    private Direction pendingDirection;
    private BacktrackManager.BacktrackPlan currentBacktrackPlan;

    // Stash detection
    private final Set<ChunkPos> processedChunks = new HashSet<>();

    public AIStashFinder() {
        super(DonutAddon.CATEGORY, "AI-StashFinder", "(Beta) Automatically mines bases below Y=-50 with safety scanning and base detection.");
    }

    @Override
    public void onActivate() {
        // Null check for safety
        if (mc.player == null) {
            error("Player is null! Cannot activate module.");
            toggle();
            return;
        }

        if (!isBaritoneAvailable()) {
            error("Baritone is required to use AI-StashFinder! Please install Baritone mod from https://github.com/cabaletta/baritone");
            toggle();
            return;
        }

        // Check Y level
        if (mc.player.getY() > yLevel.get()) {
            error("You must be below Y=" + yLevel.get() + " to use this module! Current Y: " + Math.round(mc.player.getY()));
            toggle();
            return;
        }

        // Initialize components
        try {
            directionManager = new DirectionManager();
            pathScanner = new PathScanner();
            safetyValidator = new SafetyValidator();
            backtrackManager = new BacktrackManager();
            rotationController = new RotationController();
        } catch (Exception e) {
            error("Failed to initialize components: " + e.getMessage());
            toggle();
            return;
        }

        // Reset state
        currentState = MiningState.CENTERING;
        blocksMined = 0;
        lastPos = mc.player.getPos();
        scanTicks = 0;
        processedChunks.clear();

        info("AIBaseFinder activated at Y=" + Math.round(mc.player.getY()) + ". Starting mining sequence...");
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            mc.options.attackKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
        }
        currentState = MiningState.IDLE;
        if (safetyValidator != null) {
            safetyValidator.reset();
        }
        processedChunks.clear();
        info("AIBaseFinder deactivated.");
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        // Safety check
        if (mc.player == null || mc.player.getY() > yLevel.get()) return;

        ChunkPos chunkPos = event.chunk().getPos();
        if (processedChunks.contains(chunkPos)) return;

        StashChunk chunk = new StashChunk(chunkPos);
        List<BlockEntityType<?>> selectedStorageBlocks = storageBlocks.get();

        // Scan all block entities in the chunk
        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (blockEntity instanceof MobSpawnerBlockEntity) {
                chunk.spawners++;
            } else if (selectedStorageBlocks.contains(blockEntity.getType())) {
                if (blockEntity instanceof ChestBlockEntity) {
                    chunk.chests++;
                } else if (blockEntity instanceof BarrelBlockEntity) {
                    chunk.barrels++;
                } else if (blockEntity instanceof ShulkerBoxBlockEntity) {
                    chunk.shulkers++;
                } else if (blockEntity instanceof EnderChestBlockEntity) {
                    chunk.enderChests++;
                } else if (blockEntity instanceof AbstractFurnaceBlockEntity) {
                    chunk.furnaces++;
                } else if (blockEntity instanceof DispenserBlockEntity || blockEntity instanceof DropperBlockEntity) {
                    chunk.dispensersDroppers++;
                } else if (blockEntity instanceof HopperBlockEntity) {
                    chunk.hoppers++;
                }
            }
        }

        int totalStorageBlocks = chunk.getTotal();
        boolean isBase = false;
        String foundType = "";

        // Check if it's a base
        if (chunk.spawners > 0) {
            isBase = true;
            foundType = "spawners";
        } else if (totalStorageBlocks >= baseThreshold.get()) {
            isBase = true;
            foundType = "base";
        }

        if (isBase) {
            processedChunks.add(chunkPos);
            disconnectAndNotify(chunk, foundType);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Primary safety check - prevent any operations if above Y level
        if (mc.player == null) {
            toggle();
            return;
        }

        // Check Y level every tick for safety
        if (mc.player.getY() > yLevel.get()) {
            error("Moved above Y=" + yLevel.get() + "! Disabling module for safety.");
            toggle();
            return;
        }

        // Update settings
        pathScanner.updateScanWidths(scanWidthFalling.get(), scanWidthFluids.get());
        rotationController.updateSettings(
            smoothRotation.get(),
            rotationSpeed.get(),
            rotationAcceleration.get(),
            humanLikeRotation.get(),
            overshootChance.get()
        );

        // Check if we should continue
        if (!safetyValidator.canContinue(mc.player, yLevel.get())) {
            error("Safety validation failed!");
            toggle();
            return;
        }

        // Check if stuck and should jump
        if (safetyValidator.checkStuck(mc.player)) {
            error("Stopping (Beta Version)");
            mc.player.jump();
        }

        // Check for pickaxe
        FindItemResult pickaxe = InvUtils.findInHotbar(this::isTool);
        if (!pickaxe.found()) {
            error("No pickaxe found in hotbar!");
            toggle();
            return;
        }

        if (!pickaxe.isMainHand()) {
            InvUtils.swap(pickaxe.slot(), false);
        }

        // Handle rotation
        rotationController.update();
        if (rotationController.isRotating()) {
            return;
        }

        // State machine
        try {
            switch (currentState) {
                case CENTERING -> handleCentering();
                case SCANNING -> handleScanning();
                case MINING -> handleMining();
                case HAZARD_DETECTED -> handleHazardDetected();
                case RETRACING -> handleRetracing();
                case BACKTRACKING -> handleBacktracking();
                case ROTATING -> currentState = MiningState.SCANNING;
                case STOPPED -> toggle();
            }
        } catch (Exception e) {
            error("Error in state machine: " + e.getMessage());
            toggle();
        }
    }

    private void disconnectAndNotify(StashChunk chunk, String type) {
        // Stop all actions
        if (mc.options != null) {
            mc.options.attackKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
        }

        // Create disconnect message
        String message = type.equals("spawners") ?
            "DonutClient AIStashFinder Found spawners" :
            "DonutClient AIStashFinder Found base";

        error(message);

        if (disconnectOnBaseFind.get()) {
            // Disconnect from server
            if (mc.player != null && mc.player.networkHandler != null) {
                mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal(message)));
            }

            // Disable the module
            toggle();
        } else {
            info("Base found but disconnect is disabled. Continuing mining...");
        }
    }

    private boolean isTool(ItemStack itemStack) {
        return itemStack.getItem() instanceof MiningToolItem || itemStack.getItem() instanceof ShearsItem;
    }

    private void handleCentering() {
        PlayerUtils.centerPlayer();

        float yaw = mc.player.getYaw();
        Direction initialDir = getCardinalDirection(yaw);
        directionManager.setInitialDirection(initialDir);

        float targetYaw = directionToYaw(initialDir);
        currentState = MiningState.ROTATING;

        rotationController.startRotation(targetYaw, () -> {
            backtrackManager.startNewSegment(initialDir, mc.player.getPos());
            currentState = MiningState.SCANNING;
        });
    }

    private void handleScanning() {
        BlockPos playerPos = mc.player.getBlockPos();
        Direction currentDir = directionManager.getCurrentDirection();

        PathScanner.ScanResult result = pathScanner.scanDirection(
            playerPos,
            currentDir,
            scanDepth.get(),
            scanHeight.get(),
            strictGroundCheck.get()
        );

        if (result.isSafe()) {
            currentState = MiningState.MINING;
            blocksMined = 0;
            scanTicks = 0;
            lastPos = mc.player.getPos();
            mc.options.attackKey.setPressed(true);
            mc.options.forwardKey.setPressed(true);
        } else {
            String hazardName = getHazardName(result.getHazardType());
            warning(hazardName + " detected, changing direction");
            currentState = MiningState.HAZARD_DETECTED;
        }
    }

    private void handleMining() {
        // Additional Y-level check during mining
        if (mc.player.getY() > yLevel.get()) {
            error("Moved above Y=" + yLevel.get() + " while mining! Stopping.");
            mc.options.attackKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            toggle();
            return;
        }

        Vec3d currentPos = mc.player.getPos();
        scanTicks++;

        if (scanTicks >= scanFrequency.get()) {
            scanTicks = 0;

            BlockPos playerPos = mc.player.getBlockPos();
            Direction currentDir = directionManager.getCurrentDirection();

            PathScanner.ScanResult result = pathScanner.scanDirection(
                playerPos,
                currentDir,
                scanDepth.get(),
                scanHeight.get(),
                strictGroundCheck.get()
            );

            if (!result.isSafe() && result.getHazardDistance() <= safetyMargin.get()) {
                String hazardName = getHazardName(result.getHazardType());
                warning(hazardName + " detected, changing direction");
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
                currentState = MiningState.HAZARD_DETECTED;
                return;
            }
        }

        double distanceMoved = currentPos.distanceTo(lastPos);
        if (distanceMoved >= 0.8) {
            blocksMined++;
            lastPos = currentPos;

            directionManager.recordMovement(1);
            backtrackManager.recordMovement();
        }

        if (blocksMined >= 3) {
            mc.options.attackKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);

            BlockPos playerPos = mc.player.getBlockPos();
            Direction currentDir = directionManager.getCurrentDirection();

            PathScanner.ScanResult result = pathScanner.scanDirection(
                playerPos,
                currentDir,
                scanDepth.get(),
                scanHeight.get(),
                strictGroundCheck.get()
            );

            if (!result.isSafe() && result.getHazardDistance() <= safetyMargin.get()) {
                String hazardName = getHazardName(result.getHazardType());
                warning(hazardName + " detected, changing direction");
                currentState = MiningState.HAZARD_DETECTED;
                return;
            }

            blocksMined = 0;
            scanTicks = 0;
            mc.options.attackKey.setPressed(true);
            mc.options.forwardKey.setPressed(true);
        }
    }

    private void handleHazardDetected() {
        mc.options.attackKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);

        PlayerUtils.centerPlayer();

        Direction currentDir = directionManager.getCurrentDirection();
        directionManager.recordHazard(currentDir, 0);

        DirectionManager.DirectionChoice choice = directionManager.getNextDirection();

        if (choice.needsBacktrack) {
            Direction mainTunnel = directionManager.getMainTunnel();
            currentBacktrackPlan = backtrackManager.createBacktrackPlan(mainTunnel, backtrackDistance.get());

            info("Backtracking and trying again");

            if (currentBacktrackPlan.needsRetrace) {
                // Start retrace
                float targetYaw = directionToYaw(currentBacktrackPlan.retraceDirection);
                currentState = MiningState.ROTATING;

                rotationController.startRotation(targetYaw, () -> {
                    currentState = MiningState.RETRACING;
                    backtrackManager.startRetrace(currentBacktrackPlan.retraceDistance);
                    lastPos = mc.player.getPos();
                    mc.options.forwardKey.setPressed(true);
                });
            } else {
                // Direct backtrack
                startBacktrack();
            }
            return;
        }

        if (choice.direction == null) {
            error("No safe directions found!");
            currentState = MiningState.STOPPED;
            return;
        }

        pendingDirection = choice.direction;

        float targetYaw = directionToYaw(choice.direction);
        currentState = MiningState.ROTATING;

        rotationController.startRotation(targetYaw, () -> {
            backtrackManager.startNewSegment(pendingDirection, mc.player.getPos());
            currentState = MiningState.SCANNING;
        });
    }

    private void handleRetracing() {
        Vec3d currentPos = mc.player.getPos();
        double distanceMoved = currentPos.distanceTo(lastPos);

        if (distanceMoved >= 0.8) {
            lastPos = currentPos;

            if (backtrackManager.updateRetrace()) {
                // Retrace complete, start backtrack
                mc.options.forwardKey.setPressed(false);
                startBacktrack();
            }
        }
    }

    private void startBacktrack() {
        float targetYaw = directionToYaw(currentBacktrackPlan.backtrackDirection);
        currentState = MiningState.ROTATING;

        rotationController.startRotation(targetYaw, () -> {
            currentState = MiningState.BACKTRACKING;
            backtrackManager.startBacktrack(currentBacktrackPlan.backtrackDistance);
            lastPos = mc.player.getPos();
            mc.options.forwardKey.setPressed(true);
        });
    }

    private void handleBacktracking() {
        Vec3d currentPos = mc.player.getPos();
        double distanceMoved = currentPos.distanceTo(lastPos);

        if (distanceMoved >= 0.8) {
            lastPos = currentPos;

            if (backtrackManager.updateBacktrack()) {
                // Backtrack complete
                mc.options.forwardKey.setPressed(false);

                backtrackManager.reset();
                directionManager.markBacktrackComplete();

                // Get new direction
                DirectionManager.DirectionChoice choice = directionManager.getNextDirection();

                if (choice.direction == null) {
                    error("No safe directions found after backtracking!");
                    currentState = MiningState.STOPPED;
                    return;
                }

                pendingDirection = choice.direction;

                float targetYaw = directionToYaw(choice.direction);
                currentState = MiningState.ROTATING;

                rotationController.startRotation(targetYaw, () -> {
                    backtrackManager.startNewSegment(pendingDirection, mc.player.getPos());
                    currentState = MiningState.SCANNING;
                });
            }
        }
    }

    private String getHazardName(PathScanner.HazardType type) {
        return switch (type) {
            case LAVA -> "Lava";
            case WATER -> "Water";
            case FALLING_BLOCK -> "Gravel";
            case UNSAFE_GROUND -> "Unsafe ground";
            case DANGEROUS_BLOCK -> "Dangerous block";
            default -> "Hazard";
        };
    }

    private Direction getCardinalDirection(float yaw) {
        yaw = (yaw % 360 + 360) % 360;

        if (yaw >= 315 || yaw < 45) return Direction.SOUTH;
        if (yaw >= 45 && yaw < 135) return Direction.WEST;
        if (yaw >= 135 && yaw < 225) return Direction.NORTH;
        return Direction.EAST;
    }

    private float directionToYaw(Direction dir) {
        return switch (dir) {
            case NORTH -> 180;
            case SOUTH -> 0;
            case EAST -> -90;
            case WEST -> 90;
            default -> 0;
        };
    }

    // Inner class for tracking stash contents
    private static class StashChunk {
        public final int x;
        public final int z;
        public int chests = 0;
        public int barrels = 0;
        public int shulkers = 0;
        public int enderChests = 0;
        public int furnaces = 0;
        public int dispensersDroppers = 0;
        public int hoppers = 0;
        public int spawners = 0;

        public StashChunk(ChunkPos pos) {
            this.x = pos.x * 16;
            this.z = pos.z * 16;
        }

        public int getTotal() {
            return chests + barrels + shulkers + enderChests + furnaces +
                dispensersDroppers + hoppers + spawners;
        }
    }
}
