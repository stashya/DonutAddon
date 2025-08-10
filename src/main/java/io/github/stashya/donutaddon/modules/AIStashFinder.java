package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import io.github.stashya.donutaddon.modules.AI.*;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;

import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class AIStashFinder extends Module {
    private final Set<BlockPos> hazardBlocks = new HashSet<>();
    private final Set<BlockPos> waypointBlocks = new HashSet<>(); // Debug: show waypoints
    private final Color hazardColor = new Color(255, 0, 0, 125); // Red with transparency
    private final Color hazardLineColor = new Color(255, 0, 0, 255);
    private final Color waypointColor = new Color(0, 255, 0, 125); // Green for waypoints
    private final Color waypointLineColor = new Color(0, 255, 0, 255);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    // Visible settings
    private final Setting<Integer> scanDepth = sgGeneral.add(new IntSetting.Builder()
        .name("scan-depth")
        .description("How many blocks ahead to scan for hazards.")
        .defaultValue(20)
        .range(10, 30)
        .sliderRange(10, 30)
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

    private final Setting<Boolean> showHazards = sgGeneral.add(new BoolSetting.Builder()
        .name("show-hazards")
        .description("Highlight blocks that cause direction changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> hazardShapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("hazard-shape-mode")
        .description("How to render hazard blocks.")
        .defaultValue(ShapeMode.Both)
        .visible(showHazards::get)
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

    // Debug settings
    private final Setting<Boolean> debugMode = sgDebug.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Show debug information in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showWaypoints = sgDebug.add(new BoolSetting.Builder()
        .name("show-waypoints")
        .description("Render waypoints for detours.")
        .defaultValue(true)
        .visible(debugMode::get)
        .build()
    );

    private final Setting<Boolean> printHazardMap = sgDebug.add(new BoolSetting.Builder()
        .name("print-hazard-map")
        .description("Print local hazard map periodically.")
        .defaultValue(false)
        .visible(debugMode::get)
        .build()
    );

    // Hidden settings with fixed values
    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Maximum Y level to operate at.")
        .defaultValue(-30)
        .range(-64, -10)
        .sliderRange(-64, -10)
        .visible(() -> false)
        .build()
    );

    private final Setting<Integer> scanHeight = sgSafety.add(new IntSetting.Builder()
        .name("scan-height")
        .description("How many blocks above to scan for falling blocks.")
        .defaultValue(4)
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
        .defaultValue(2)
        .range(2, 6)
        .sliderRange(2, 6)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> strictGroundCheck = sgSafety.add(new BoolSetting.Builder()
        .name("strict-ground-check")
        .description("Require solid ground for entire scan area.")
        .defaultValue(false)
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

    // Add to your settings section
    private final Setting<Boolean> workInMenus = sgGeneral.add(new BoolSetting.Builder()
        .name("work-in-menus")
        .description("Continue mining while in GUIs/menus.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseForAutoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-for-auto-eat")
        .description("Pauses mining when AutoEat is active and eating.")
        .defaultValue(true)
        .build()
    );


    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);  // This is the key part!
    }

    private void stopMovement() {
        setPressed(mc.options.attackKey, false);
        setPressed(mc.options.forwardKey, false);
    }

    private void startMining() {
        setPressed(mc.options.attackKey, true);
        setPressed(mc.options.forwardKey, true);
    }

    // Module components
    private MiningState currentState = MiningState.IDLE;
    private DirectionalPathfinder pathfinder;
    private PathScanner pathScanner;
    private SafetyValidator safetyValidator;
    private RotationController rotationController;
    private final SimpleSneakCentering centeringHelper = new SimpleSneakCentering();
    private ParkourHelper parkourHelper;

    // Mining tracking
    private int blocksMined = 0;
    private int totalBlocksMined = 0;
    private Vec3d lastPos = Vec3d.ZERO;
    private int scanTicks = 0;
    private static final int SCAN_INTERVAL = 20; // Scan every 20 ticks (1 second)

    // Current operation tracking
    private BlockPos currentWaypoint;
    private PathScanner.ScanResult lastHazardDetected;
    private Direction pendingDirection;

    // Debug tracking
    private int tickCounter = 0;
    private long moduleStartTime = 0;

    private boolean wasPausedForEating = false;
    private MiningState stateBeforeEating = MiningState.IDLE;

    // Stash detection
    private final Set<ChunkPos> processedChunks = new HashSet<>();

    public AIStashFinder() {
        super(DonutAddon.CATEGORY, "AI-StashFinder", "(Beta) Automatically mines with directional persistence and intelligent pathfinding.");
    }

    @EventHandler
    public void onRender(Render3DEvent event) {
        // Render hazards
        if (showHazards.get() && !hazardBlocks.isEmpty()) {
            synchronized (hazardBlocks) {
                for (BlockPos pos : hazardBlocks) {
                    event.renderer.box(pos, hazardLineColor, hazardColor, hazardShapeMode.get(), 0);
                }
            }
        }

        // Render waypoints (debug)
        if (debugMode.get() && showWaypoints.get() && !waypointBlocks.isEmpty()) {
            synchronized (waypointBlocks) {
                for (BlockPos pos : waypointBlocks) {
                    event.renderer.box(pos, waypointLineColor, waypointColor, ShapeMode.Both, 0);
                }
            }
        }
    }

    @Override
    public void onActivate() {
        moduleStartTime = System.currentTimeMillis();

        // Null check for safety
        if (mc.player == null) {
            error("Player is null! Cannot activate module.");
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
            pathScanner = new PathScanner();
            pathScanner.updateTunnelDimensions(3, 3);
            pathfinder = new DirectionalPathfinder(pathScanner);
            safetyValidator = new SafetyValidator();
            rotationController = new RotationController();
            parkourHelper = new ParkourHelper();
        } catch (Exception e) {
            error("Failed to initialize components: " + e.getMessage());
            toggle();
            return;
        }

        // Reset state
        currentState = MiningState.CENTERING;
        blocksMined = 0;
        totalBlocksMined = 0;
        lastPos = mc.player.getPos();
        scanTicks = 0;
        tickCounter = 0;
        processedChunks.clear();

        info("AIStashFinder activated at Y=" + Math.round(mc.player.getY()));
        if (debugMode.get()) {
            info("Debug mode enabled - verbose logging active");
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            stopMovement();
        }

        // Stop centering if active
        centeringHelper.stopCentering();

        synchronized (hazardBlocks) {
            hazardBlocks.clear();
        }
        synchronized (waypointBlocks) {
            waypointBlocks.clear();
        }
        currentState = MiningState.IDLE;
        if (safetyValidator != null) {
            safetyValidator.reset();
        }
        if (parkourHelper != null) {
            parkourHelper.reset();
        }
        processedChunks.clear();

        long runtime = (System.currentTimeMillis() - moduleStartTime) / 1000;
        info("AIStashFinder deactivated. Runtime: " + runtime + "s, Blocks mined: " + totalBlocksMined);
    }

    @EventHandler
    public void onChunkData(ChunkDataEvent event) {
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
    public void onTick(TickEvent.Pre event) {
        tickCounter++;

        // Check if we should pause for menus
        if (!workInMenus.get() && mc.currentScreen != null) {
            // Stop movement when in menus if setting is disabled
            if (mc.options != null) {
                stopMovement();
            }
            return;
        }

        // Primary safety check
        if (mc.player == null) {
            toggle();
            return;
        }

        if (pauseForAutoEat.get()) {
            AutoEat autoEat = Modules.get().get(AutoEat.class);

            if (autoEat != null && autoEat.isActive()) {
                // Check if AutoEat is currently eating or should start eating
                if (autoEat.eating || autoEat.shouldEat()) {
                    // Pause mining if not already paused
                    if (!wasPausedForEating) {
                        wasPausedForEating = true;
                        stateBeforeEating = currentState; // Save current state

                        // Stop all movement and mining
                        stopMovement();

                        // Set state to a paused state (you might want to add a PAUSED_EATING state)
                        currentState = MiningState.IDLE;

                        if (debugMode.get()) {
                            info("Pausing for AutoEat (hunger/health low)");
                        }
                    }
                    return; // Skip the rest of the tick while eating
                } else if (wasPausedForEating) {
                    // AutoEat finished, resume mining
                    wasPausedForEating = false;
                    currentState = stateBeforeEating; // Restore previous state

                    if (debugMode.get()) {
                        info("AutoEat finished, resuming mining");
                    }
                }
            }
        }

        // Check Y level every tick for safety
        if (mc.player.getY() > yLevel.get()) {
            error("Moved above Y=" + yLevel.get() + "! Disabling module for safety.");
            toggle();
            return;
        }



        if (!safetyValidator.canContinue(mc.player, yLevel.get())) {
            // Check specific reason for failure
            ItemStack mainHand = mc.player.getMainHandStack();
            if (mainHand != null && !mainHand.isEmpty()) {
                double durabilityPercent = SafetyValidator.getToolDurabilityPercent(mainHand);
                if (durabilityPercent <= 0.10) {
                    error("Pickaxe durability below 10% (" +
                        String.format("%.1f%%", durabilityPercent * 100) +
                        ")! Stopping to prevent tool break.");
                } else if (mc.player.getHealth() < 10) {
                    error("Low health! Need to heal before continuing.");
                } else {
                    error("Safety check failed - check health and tool durability");
                }
            } else {
                error("No tool in hand!");
            }
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

        if (parkourHelper != null) {
            parkourHelper.update();
        }

        // Check if we should continue
        if (!safetyValidator.canContinue(mc.player, yLevel.get())) {
            error("Safety check failed - need full health or better tool");
            toggle();
            return;
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

        // Debug output every 100 ticks (5 seconds)
        if (debugMode.get() && tickCounter % 100 == 0) {
            printDebugInfo();
        }

        // State machine
        try {
            switch (currentState) {
                case CENTERING -> handleCentering();
                case SCANNING_PRIMARY -> handleScanningPrimary();
                case MINING_PRIMARY -> handleMiningPrimary();
                case HAZARD_DETECTED -> handleHazardDetected();
                case CALCULATING_DETOUR -> handleCalculatingDetour();
                case FOLLOWING_DETOUR -> handleFollowingDetour();
                case CHANGING_DIRECTION -> handleChangingDirection();
                case ROTATING -> currentState = MiningState.SCANNING_PRIMARY;
                case STOPPED -> {
                    error("All directions blocked - stopping");
                    toggle();
                }
            }
        } catch (Exception e) {
            error("Error in state machine: " + e.getMessage());
            if (debugMode.get()) {
                e.printStackTrace();
            }
            toggle();
        }
    }

    private void handleCentering() {
        if (!centeringHelper.isCentering()) {
            if (!centeringHelper.startCentering()) {
                // Already centered
                proceedAfterCentering();
                return;
            }
        }

        if (!centeringHelper.tick()) {
            // Centering complete
            proceedAfterCentering();
        }
    }

    private void proceedAfterCentering() {
        float yaw = mc.player.getYaw();
        Direction initialDir = getCardinalDirection(yaw);
        pathfinder.setInitialDirection(initialDir);

        float targetYaw = directionToYaw(initialDir);
        final Direction finalDir = initialDir; // Make it final for lambda
        currentState = MiningState.ROTATING;

        rotationController.startRotation(targetYaw, 0.0f, () -> {
            currentState = MiningState.SCANNING_PRIMARY;
            if (debugMode.get()) {
                info("Initial direction set to " + finalDir.getName() + ", starting mining");
            }
        });
    }

    private void handleScanningPrimary() {
        BlockPos playerPos = mc.player.getBlockPos();
        Direction primaryDir = pathfinder.getPrimaryDirection();

        if (debugMode.get()) {
            System.out.println("DEBUG: Scanning primary direction " + primaryDir.getName());
        }

        // Check for parkour opportunity
        if (!parkourHelper.isJumping()) {
            ParkourHelper.JumpCheck jumpCheck = parkourHelper.checkJumpOpportunity(mc.player, primaryDir);
            if (jumpCheck.shouldJump) {
                currentState = MiningState.MINING_PRIMARY;
                scanTicks = 0;
                lastPos = mc.player.getPos();
                startMining();
                if (debugMode.get()) {
                    info("Jumping over obstacle: " + jumpCheck.reason);
                }
                return;
            }
        }

        // Scan ahead
        PathScanner.ScanResult result = pathScanner.scanDirection(
            playerPos,
            primaryDir,
            scanDepth.get(),
            scanHeight.get(),
            strictGroundCheck.get()
        );

        if (result.isSafe()) {
            synchronized (hazardBlocks) {
                hazardBlocks.clear();
            }
            currentState = MiningState.MINING_PRIMARY;
            scanTicks = 0;
            lastPos = mc.player.getPos();
            startMining();

            if (debugMode.get()) {
                System.out.println("DEBUG: Path clear, starting mining");
            }
        } else {
            lastHazardDetected = result;
            currentState = MiningState.HAZARD_DETECTED;

            if (debugMode.get()) {
                String hazardName = getHazardName(result.getHazardType());
                warning(hazardName + " detected at distance " + result.getHazardDistance());
            }
        }
    }

    private void handleMiningPrimary() {
        // Y-level check
        if (mc.player.getY() > yLevel.get()) {
            error("Moved above Y=" + yLevel.get() + " while mining!");
            stopMovement();
            toggle();
            return;
        }

        Vec3d currentPos = mc.player.getPos();
        scanTicks++;

        // Check for parkour
        if (!parkourHelper.isJumping()) {
            Direction primaryDir = pathfinder.getPrimaryDirection();
            ParkourHelper.JumpCheck jumpCheck = parkourHelper.checkJumpOpportunity(mc.player, primaryDir);

            if (jumpCheck.shouldJump) {
                if (debugMode.get()) {
                    info("Jumping: " + jumpCheck.reason);
                }
                if (parkourHelper.startJump(jumpCheck.targetPos)) {
                    return;
                }
            }
        }

        // Quick scan every SCAN_INTERVAL ticks
        if (scanTicks >= SCAN_INTERVAL) {
            scanTicks = 0;

            BlockPos playerPos = mc.player.getBlockPos();
            Direction primaryDir = pathfinder.getPrimaryDirection();

            PathScanner.ScanResult result = pathScanner.scanDirection(
                playerPos,
                primaryDir,
                scanDepth.get(),
                scanHeight.get(),
                false // Non-strict for speed
            );

            if (!result.isSafe()) {
                if (debugMode.get()) {
                    String hazardName = getHazardName(result.getHazardType());
                    warning(hazardName + " detected at distance " + result.getHazardDistance() + ", stopping");
                }

                stopMovement();
                lastHazardDetected = result;
                currentState = MiningState.HAZARD_DETECTED;

                // Update hazard blocks for rendering
                if (showHazards.get()) {
                    synchronized (hazardBlocks) {
                        hazardBlocks.clear();
                        hazardBlocks.addAll(result.getHazardPositions());
                    }
                }
                return;
            }
        }

        // Track movement
        double distanceMoved = currentPos.distanceTo(lastPos);
        if (distanceMoved >= 0.8) {
            blocksMined++;
            totalBlocksMined++;
            lastPos = currentPos;

            if (debugMode.get() && blocksMined % 10 == 0) {
                System.out.println("DEBUG: Mined " + blocksMined + " blocks in current stretch, " +
                    totalBlocksMined + " total");
            }
        }
    }

    private void handleHazardDetected() {
        stopMovement();

        if (debugMode.get()) {
            System.out.println("\n=== HAZARD DETECTED STATE ===");
            System.out.println("Hazard type: " + lastHazardDetected.getHazardType());
            System.out.println("Distance: " + lastHazardDetected.getHazardDistance());

            // Special message for sandwich traps
            if (lastHazardDetected.getHazardType() == PathScanner.HazardType.SANDWICH_TRAP) {
                System.out.println("SANDWICH TRAP: Cannot mine or jump - must detour!");
                warning("Sandwich trap detected - blocks prevent mining and jumping");
            }
        }

        currentState = MiningState.CALCULATING_DETOUR;
    }

    private void handleCalculatingDetour() {
        if (debugMode.get()) {
            System.out.println("DEBUG: Calculating best path around hazard");
        }

        BlockPos playerPos = mc.player.getBlockPos();
        DirectionalPathfinder.PathPlan plan = pathfinder.calculateDetour(playerPos, lastHazardDetected);

        if (plan.newPrimaryDirection != null) {
            // Need to change primary direction
            pendingDirection = plan.newPrimaryDirection;
            currentState = MiningState.CHANGING_DIRECTION;

            if (debugMode.get()) {
                warning("Completely blocked! Changing primary direction to " + pendingDirection.getName());
            }
        } else if (plan.needsDetour) {
            // Start following detour
            currentWaypoint = pathfinder.getNextWaypoint();
            if (currentWaypoint != null) {
                currentState = MiningState.FOLLOWING_DETOUR;

                // Update waypoint blocks for rendering using the PEEK method
                if (debugMode.get() && showWaypoints.get()) {
                    synchronized (waypointBlocks) {
                        waypointBlocks.clear();
                        waypointBlocks.add(currentWaypoint); // Add current

                        // Use peekAllWaypoints to get remaining without consuming
                        Queue<BlockPos> remainingWaypoints = pathfinder.peekAllWaypoints();
                        waypointBlocks.addAll(remainingWaypoints);
                    }
                }

                if (debugMode.get()) {
                    info("Starting detour with " + (pathfinder.peekAllWaypoints().size() + 1) + " waypoints");
                }
            }
        } else {
            // No path possible
            currentState = MiningState.STOPPED;
        }
    }

    private boolean isAtBlockPosition(BlockPos playerPos, BlockPos target) {
        // Check if player is exactly at the target block position
        return playerPos.getX() == target.getX() &&
            playerPos.getY() == target.getY() &&
            playerPos.getZ() == target.getZ();
    }

    private boolean isCenteredOnBlock(BlockPos blockPos) {
        // Check if player is centered on the block (within 0.25 blocks of center)
        double offsetX = Math.abs(mc.player.getX() - (blockPos.getX() + 0.5));
        double offsetZ = Math.abs(mc.player.getZ() - (blockPos.getZ() + 0.5));
        return offsetX <= 0.25 && offsetZ <= 0.25;
    }

    private void handleFollowingDetour() {
        // Check if we have a current waypoint
        if (currentWaypoint == null) {
            // Get next waypoint
            currentWaypoint = pathfinder.getNextWaypoint();
            if (currentWaypoint == null) {
                // Detour complete
                pathfinder.completeDetour();
                currentState = MiningState.SCANNING_PRIMARY;

                synchronized (waypointBlocks) {
                    waypointBlocks.clear();
                }

                if (debugMode.get()) {
                    if (pathfinder.getDebugInfo().contains("Approaching")) {
                        info("Reached approach point, rescanning for close hazard");
                    } else {
                        info("Detour complete, resuming primary direction");
                    }
                }
                return;
            }

            // Update visual waypoints
            if (debugMode.get() && showWaypoints.get()) {
                synchronized (waypointBlocks) {
                    waypointBlocks.clear();
                    waypointBlocks.add(currentWaypoint);
                    waypointBlocks.addAll(pathfinder.peekAllWaypoints());
                }
            }

            if (debugMode.get()) {
                System.out.println("DEBUG: Moving to waypoint: " + currentWaypoint);
            }
        }

        BlockPos playerPos = mc.player.getBlockPos();


        // Check for parkour opportunities
        if (!parkourHelper.isJumping()) {
            Direction dirToWaypoint = getDirectionToward(playerPos, currentWaypoint);
            if (dirToWaypoint != null) {
                ParkourHelper.JumpCheck jumpCheck = parkourHelper.checkJumpOpportunity(mc.player, dirToWaypoint);
                if (jumpCheck.shouldJump) {
                    if (debugMode.get()) {
                        info("Jumping during detour: " + jumpCheck.reason);
                    }
                    if (parkourHelper.startJump(jumpCheck.targetPos)) {
                        // Continue mining while jumping
                        startMining();
                        return;
                    }
                }
            }
        }

        // Check if we've reached the waypoint block position
        double distanceToWaypoint = Math.sqrt(
            Math.pow(playerPos.getX() - currentWaypoint.getX(), 2) +
                Math.pow(playerPos.getZ() - currentWaypoint.getZ(), 2)
        );

        if (distanceToWaypoint < 0.5) {
            // We're at or very close to the waypoint
            if (debugMode.get()) {
                System.out.println("DEBUG: Reached waypoint " + currentWaypoint);
            }
            currentWaypoint = null; // Move to next waypoint
            return;
        }

        // Calculate direction to waypoint
        Direction dirToWaypoint = getDirectionToward(playerPos, currentWaypoint);
        if (dirToWaypoint == null) {
            // Can't determine direction (might be diagonal or on different Y)
            // Try to get closer by mining in general direction
            int dx = currentWaypoint.getX() - playerPos.getX();
            int dz = currentWaypoint.getZ() - playerPos.getZ();

            if (Math.abs(dx) > Math.abs(dz)) {
                dirToWaypoint = dx > 0 ? Direction.EAST : Direction.WEST;
            } else if (dz != 0) {
                dirToWaypoint = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            } else {
                // We're close enough, move to next waypoint
                currentWaypoint = null;
                return;
            }
        }

        // Check if we need to rotate
        float currentYaw = mc.player.getYaw();
        float targetYaw = directionToYaw(dirToWaypoint);
        float yawDiff = Math.abs(MathHelper.wrapDegrees(currentYaw - targetYaw));

        if (yawDiff > 15) {
            // Need to rotate
            stopMovement();

            currentState = MiningState.ROTATING;
            rotationController.startRotation(targetYaw, 0.0f, () -> {
                currentState = MiningState.FOLLOWING_DETOUR;
            });
            return;
        }

        // Mine toward waypoint
        startMining();

        // Update last position for movement tracking
        lastPos = mc.player.getPos();

        // Safety scan while following waypoint
        scanTicks++;
        if (scanTicks >= 20) { // Check every second
            scanTicks = 0;

            PathScanner.ScanResult quickScan = pathScanner.scanDirection(
                playerPos, dirToWaypoint, 3, 4, false
            );

            if (!quickScan.isSafe() && quickScan.getHazardDistance() <= 2) {
                // Immediate danger while following waypoint!
                if (debugMode.get()) {
                    warning("Hazard detected while following detour! Type: " + quickScan.getHazardType());
                }

                // Stop and recalculate
                stopMovement();

                // Clear current detour and recalculate
                currentWaypoint = null;
                pathfinder.completeDetour();
                lastHazardDetected = quickScan;
                currentState = MiningState.CALCULATING_DETOUR;
            }
        }
    }

    private void handleChangingDirection() {
        if (pendingDirection == null) {
            currentState = MiningState.STOPPED;
            return;
        }

        // First, center before rotating to new direction
        if (!centeringHelper.isCentering()) {
            // Check if we need to center
            if (!isCenteredOnBlock(mc.player.getBlockPos())) {
                if (debugMode.get()) {
                    info("Centering before changing direction to " + pendingDirection.getName());
                }

                if (centeringHelper.startCentering()) {
                    // Started centering, stay in this state
                    return;
                }
            }
        } else {
            // Currently centering, check if done
            if (!centeringHelper.tick()) {
                // Centering complete, now we can rotate
                if (debugMode.get()) {
                    info("Centering complete, now rotating to " + pendingDirection.getName());
                }
            } else {
                // Still centering
                return;
            }
        }

        // We're centered, now perform the rotation
        float targetYaw = directionToYaw(pendingDirection);
        currentState = MiningState.ROTATING;

        rotationController.startRotation(targetYaw, 0.0f, () -> {
            pathfinder.setInitialDirection(pendingDirection);
            currentState = MiningState.SCANNING_PRIMARY;
            pendingDirection = null;

            if (debugMode.get()) {
                info("Direction change complete, now mining " + pathfinder.getPrimaryDirection().getName());
            }
        });
    }

    private Direction getDirectionToward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();

        // Only return cardinal directions, ignore Y differences
        // Choose the direction with greater absolute difference
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (Math.abs(dz) > Math.abs(dx)) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        } else if (dx != 0) {
            // Equal distance, prefer X axis
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        return null; // Same position horizontally
    }

    private void printDebugInfo() {
        System.out.println("\n=== DEBUG INFO (Tick " + tickCounter + ") ===");
        System.out.println("State: " + currentState);
        System.out.println("Blocks mined: " + totalBlocksMined);
        System.out.println("Position: " + mc.player.getBlockPos());
        System.out.println(pathfinder.getDebugInfo());
    }

    private void disconnectAndNotify(StashChunk chunk, String type) {
        // Stop all actions
        if (mc.options != null) {
            stopMovement();
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

    private String getHazardName(PathScanner.HazardType type) {
        return switch (type) {
            case LAVA -> "Lava";
            case WATER -> "Water";
            case FALLING_BLOCK -> "Gravel/Sand";
            case UNSAFE_GROUND -> "Unsafe ground";
            case DANGEROUS_BLOCK -> "Dangerous block";
            case SANDWICH_TRAP -> "Sandwich trap (blocked mining)";  // NEW
            default -> "Unknown hazard";
        };
    }

    private Direction getCardinalDirection(float yaw) {
        // Normalize yaw to 0-360 range
        yaw = (yaw % 360 + 360) % 360;

        // Only return cardinal directions, never UP or DOWN
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
