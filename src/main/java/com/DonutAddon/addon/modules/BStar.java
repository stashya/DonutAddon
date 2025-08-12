package com.DonutAddon.addon.modules;

import com.DonutAddon.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import com.DonutAddon.addon.modules.AI.*;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MiningToolItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.item.Items;
import net.minecraft.world.World;
import meteordevelopment.meteorclient.systems.modules.player.EXPThrower;
import net.minecraft.item.Items;
import net.minecraft.world.chunk.Chunk;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class BStar extends Module {
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
    private final SettingGroup sgRTP = settings.createGroup("RTP");

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
        .name("log-when-base-or-spawners")
        .description("Disconnect from server when a base/spawner is found.")
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
        .name("Min-Storage-for-base")
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

    // Hidden settings with fixed values
    private final Setting<Integer> yLevel = sgGeneral.add(new IntSetting.Builder()
        .name("y-level")
        .description("Maximum Y level to operate at.")
        .defaultValue(-20)
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
        .visible(() -> true)
        .build()
    );

    private final Setting<Double> rotationAcceleration = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-acceleration")
        .description("How quickly rotation speeds up and slows down.")
        .defaultValue(0.8)
        .range(0.1, 2.0)
        .sliderRange(0.1, 2.0)
        .visible(() -> true)
        .build()
    );

    private final Setting<Double> overshootChance = sgRotation.add(new DoubleSetting.Builder()
        .name("overshoot-chance")
        .description("Chance to overshoot target rotation.")
        .defaultValue(0.3)
        .range(0, 1)
        .sliderRange(0, 1)
        .visible(() -> true)
        .build()
    );

    // Add to your settings section
    private final Setting<Boolean> workInMenus = sgGeneral.add(new BoolSetting.Builder()
        .name("work-in-menus")
        .description("Continue mining while in GUIs/menus.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseForAutoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-for-auto-eat")
        .description("Pauses mining when AutoEat is active and eating.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> rtpWhenStuck = sgRTP.add(new BoolSetting.Builder()
        .name("rtp-when-stuck")
        .description("Use RTP to escape when no paths are found.")
        .defaultValue(false)
        .build()
    );
    private final Setting<RTPRegion> rtpRegion = sgRTP.add(new EnumSetting.Builder<RTPRegion>()
        .name("rtp-region")
        .description("The region to RTP to when stuck.")
        .defaultValue(RTPRegion.EU_CENTRAL)
        .visible(rtpWhenStuck::get)
        .build()
    );

    private final Setting<Integer> mineDownTarget = sgRTP.add(new IntSetting.Builder()
        .name("Y-level-to-mine-down-to")
        .description("Y level to mine down to after RTP.")
        .defaultValue(-30)
        .range(-64, -10)
        .sliderRange(-64, -10)
        .visible(rtpWhenStuck::get)
        .build()
    );

    private final Setting<Integer> groundScanSize = sgRTP.add(new IntSetting.Builder()
        .name("ground-scan-size")
        .description("Size of area to scan below player after RTP (NxN).")
        .defaultValue(5)
        .range(3, 9)
        .sliderRange(3, 9)
        .visible(() -> false)
        .build()
    );

    // RTP Enum (same structure as AutoRTP)
    public enum RTPRegion {
        EU_CENTRAL("Eu Central", "eu central"),
        EU_WEST("Eu West", "eu west"),
        NA_EAST("Na East", "east"),
        NA_WEST("Na West", "west"),
        OCEANIA("Oceania", "oceania"),
        ASIA("Asia", "asia");

        private final String name;
        private final String command;

        RTPRegion(String name, String command) {
            this.name = name;
            this.command = command;
        }

        @Override
        public String toString() {
            return name;
        }

        public String getCommand() {
            return command;
        }
    }
    private final Setting<Boolean> autoRepair = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-repair")
        .description("Automatically throw EXP bottles to repair pickaxe when durability is low.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> repairThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("repair-threshold")
        .description("Durability percentage to trigger auto repair.")
        .defaultValue(20.0)
        .range(10.0, 50.0)
        .sliderRange(10.0, 50.0)
        .visible(autoRepair::get)
        .build()
    );

    private final Setting<Integer> repairDuration = sgGeneral.add(new IntSetting.Builder()
        .name("repair-duration")
        .description("How many seconds to throw EXP bottles.")
        .defaultValue(3)
        .range(1, 5)
        .sliderRange(1, 5)
        .visible(autoRepair::get)
        .build()
    );

    private final Setting<Boolean> detectPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-players")
        .description("Disconnect when players are detected in render distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> playerCheckInterval = sgGeneral.add(new IntSetting.Builder()
        .name("player-check-interval")
        .description("How often to check for players (in ticks).")
        .defaultValue(20)
        .range(10, 60)
        .sliderRange(10, 60)
        .visible(detectPlayers::get)
        .build()
    );


    private void setPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);  // This is the key part!
    }

    private void stopMovement() {
        setPressed(mc.options.attackKey, false);
        setPressed(mc.options.forwardKey, false);
        setPressed(mc.options.sneakKey, false); // ADD THIS to always release sneak
    }

    private void startMining() {
        setPressed(mc.options.attackKey, true);
        setPressed(mc.options.forwardKey, true);
        mc.player.swingHand(mc.player.getActiveHand());
    }

    // Module components
    private MiningState currentState = MiningState.IDLE;
    private DirectionalPathfinder pathfinder;
    private PathScanner pathScanner;
    private SafetyValidator safetyValidator;
    private RotationController rotationController;
    private final SimpleSneakCentering centeringHelper = new SimpleSneakCentering();
    private ParkourHelper parkourHelper;
    private boolean isDetouring = false;

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

    private long lastRtpTime = 0;
    private int rtpWaitTicks = 0;
    private int rtpAttempts = 0;
    private BlockPos preRtpPos = null;
    private boolean rtpCommandSent = false;
    private boolean hasReachedMineDepth = false;
    private int mineDownScanTicks = 0;
    private static final int RTP_COOLDOWN_SECONDS = 16;
    private static final int RTP_WAIT_TICKS = 200; // 10 seconds
    private static final int MINE_DOWN_SCAN_INTERVAL = 2; // Scan every 2 ticks while mining down
    private static final Pattern RTP_COOLDOWN_PATTERN = Pattern.compile("You can't rtp for another (\\d+)s");

    private boolean isThrowingExp = false;
    private MiningState stateBeforeExp = MiningState.IDLE;
    private int expThrowTicks = 0;
    private static final int EXP_THROW_DURATION = 60; // 3 seconds (60 ticks)
    private static final double REPAIR_DURABILITY_THRESHOLD = 0.20; // 20% durability
    private long lastExpThrowTime = 0;
    private static final long EXP_THROW_COOLDOWN = 10000; // 10 second cooldown between repairs

    private boolean wasPausedForEating = false;
    private MiningState stateBeforeEating = MiningState.IDLE;

    private boolean inRTPRecovery = false;

    // Add this field to track if we've initiated mining down
    private boolean miningDownInitiated = false;
    private int miningDownToggleTicks = 0;


    // Stash detection
    private final Set<ChunkPos> processedChunks = new HashSet<>();

    private boolean isActivelyMining() {
        return currentState == MiningState.MINING_PRIMARY ||
            currentState == MiningState.FOLLOWING_DETOUR;
    }

    public BStar() {
        super(DonutAddon.CATEGORY, "BStar", "(Beta) Automatically mines with directional persistence and intelligent pathfinding. B* method");
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
    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!rtpWhenStuck.get()) return;

        String message = event.getMessage().getString();

        // Check for RTP cooldown message
        Matcher cooldownMatcher = RTP_COOLDOWN_PATTERN.matcher(message);
        if (cooldownMatcher.find() && currentState == MiningState.RTP_INITIATED) {
            int remainingSeconds = Integer.parseInt(cooldownMatcher.group(1));

            if (debugMode.get()) {
                warning("RTP on cooldown for " + remainingSeconds + " more seconds");
            }

            // Set to cooldown state
            currentState = MiningState.RTP_COOLDOWN;
            rtpWaitTicks = (RTP_COOLDOWN_SECONDS - remainingSeconds) * 20;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!rtpWhenStuck.get()) return;
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;

        // Handle teleport during RTP states
        if (currentState == MiningState.RTP_INITIATED || currentState == MiningState.RTP_WAITING) {
            // Set recovery flag when we detect teleport
            inRTPRecovery = true;

            // Schedule position check after teleport
            new Thread(() -> {
                try {
                    Thread.sleep(100); // Wait for position to update
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                mc.execute(() -> {
                    if (mc.player != null) {
                        BlockPos newPos = mc.player.getBlockPos();

                        // Check if we actually teleported (moved significantly)
                        if (preRtpPos != null) {
                            double distance = Math.sqrt(
                                Math.pow(newPos.getX() - preRtpPos.getX(), 2) +
                                    Math.pow(newPos.getZ() - preRtpPos.getZ(), 2)
                            );

                            if (distance > 1000) { // Teleported far away
                                if (debugMode.get()) {
                                    info("RTP teleport detected! New position: " + newPos);
                                    info("Current Y: " + newPos.getY() + " (will mine down to Y=" + mineDownTarget.get() + ")");
                                }

                                lastRtpTime = System.currentTimeMillis();
                                inRTPRecovery = true; // Ensure flag is set

                                // Transition to ground scanning
                                currentState = MiningState.RTP_SCANNING_GROUND;
                                rtpWaitTicks = 0;
                            }
                        }
                    }
                });
            }).start();
        }
    }

    private void handleRTPInitiated() {
        rtpWaitTicks++;

        // Wait a bit for command to process
        if (rtpWaitTicks > 20) { // 1 second
            currentState = MiningState.RTP_WAITING;
            rtpWaitTicks = 0;
        }
    }
    private void handleRTPWaiting() {
        rtpWaitTicks++;

        // Timeout after 10 seconds
        if (rtpWaitTicks > RTP_WAIT_TICKS) {
            if (debugMode.get()) {
                warning("RTP timeout - may have failed or be on cooldown");
            }

            // Try again or fall back to stopped
            if (rtpAttempts < 3) {
                initiateRTP();
            } else {
                error("RTP failed after 3 attempts - stopping module");
                toggle();
            }
        }
    }

    private int searchChunkForSpawnersOptimized(Chunk chunk) {
        int foundSpawners = 0;
        BlockPos.Mutable blockPos = new BlockPos.Mutable();

        // For underground mining, we don't need to scan the entire height
        // BlockESP uses heightmap, but we can be smarter for underground
        int playerY = (int) mc.player.getY();

        // Only scan if we're underground
        if (playerY > 64) return 0; // Don't scan if above ground

        int minY = Math.max(mc.world.getBottomY(), -64);
        int maxY = Math.min(playerY + 20, 20); // Spawners rarely generate above Y=20

        for (int x = chunk.getPos().getStartX(); x <= chunk.getPos().getEndX(); x++) {
            for (int z = chunk.getPos().getStartZ(); z <= chunk.getPos().getEndZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    blockPos.set(x, y, z);
                    BlockState blockState = chunk.getBlockState(blockPos);

                    if (blockState.getBlock() == Blocks.SPAWNER) {
                        foundSpawners++;

                        if (debugMode.get()) {
                            info("Spawner Found, use BlockESP to see it");
                        }

                        return 1;
                    }
                }
            }
        }

        return foundSpawners;
    }
    private void handleRTPCooldown() {
        rtpWaitTicks++;

        int cooldownTicks = RTP_COOLDOWN_SECONDS * 20;
        if (rtpWaitTicks >= cooldownTicks) {
            if (debugMode.get()) {
                info("RTP cooldown complete, retrying...");
            }
            initiateRTP();
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

        // Initialize components
        try {
            pathScanner = new PathScanner();
            pathScanner.updateTunnelDimensions(3, 3);
            pathfinder = new DirectionalPathfinder(pathScanner);
            safetyValidator = new SafetyValidator();
            rotationController = new RotationController();
            rotationController.setPreciseLanding(false);
            parkourHelper = new ParkourHelper();
        } catch (Exception e) {
            error("Failed to initialize components: " + e.getMessage());
            toggle();
            return;
        }

        // Reset state
        blocksMined = 0;
        totalBlocksMined = 0;
        lastPos = mc.player.getPos();
        scanTicks = 0;
        tickCounter = 0;
        processedChunks.clear();

        // Reset RTP state
        lastRtpTime = 0;
        rtpWaitTicks = 0;
        rtpAttempts = 0;
        preRtpPos = null;
        rtpCommandSent = false;
        hasReachedMineDepth = false;
        mineDownScanTicks = 0;
        inRTPRecovery = false;

        info("BStar activated at Y=" + Math.round(mc.player.getY()));

        // Check if we're starting above ground - always mine down if above target
        if (mc.player.getY() > yLevel.get()) {
            warning("Started above Y=" + yLevel.get() + " - will scan ground and mine down to target depth");
            // Set to scanning ground state to check for fluids and mine down
            currentState = MiningState.RTP_SCANNING_GROUND;
            inRTPRecovery = true;  // Use the recovery flag to bypass Y-level checks
        } else {
            // Normal underground start
            currentState = MiningState.CENTERING;
        }

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

        if (isThrowingExp) {
            EXPThrower expThrower = Modules.get().get(EXPThrower.class);
            if (expThrower != null && expThrower.isActive()) {
                expThrower.toggle();
            }
            isThrowingExp = false;
        }

        currentState = MiningState.IDLE;
        inRTPRecovery = false;

        if (safetyValidator != null) {
            safetyValidator.reset();
        }
        if (parkourHelper != null) {
            parkourHelper.reset();
        }
        processedChunks.clear();

        long runtime = (System.currentTimeMillis() - moduleStartTime) / 1000;
        info("Bstar deactivated. Runtime: " + runtime + "s, Blocks mined: " + totalBlocksMined);
    }

    @EventHandler
    public void onChunkData(ChunkDataEvent event) {
        // Safety check
        if (mc.player == null || mc.player.getY() > yLevel.get()) return;

        ChunkPos chunkPos = event.chunk().getPos();
        if (processedChunks.contains(chunkPos)) return;

        StashChunk chunk = new StashChunk(chunkPos);
        List<BlockEntityType<?>> selectedStorageBlocks = storageBlocks.get();

        // Use BlockESP-style block scanning for spawners
        chunk.spawners = searchChunkForSpawnersOptimized(event.chunk());

        // Keep your existing block entity detection for storage blocks (this works fine)
        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (selectedStorageBlocks.contains(blockEntity.getType())) {
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

            if (debugMode.get()) {
                info("Found " + chunk.spawners + " spawner(s) in chunk [" + chunkPos.x + ", " + chunkPos.z + "]");
            }
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
        rotationController.update();
        // MOVED THIS CHECK AFTER EATING CHECK AND ADDED RTP STATE CHECK
        if (pauseForAutoEat.get()) {
            AutoEat autoEat = Modules.get().get(AutoEat.class);

            if (autoEat != null && autoEat.isActive()) {
                if (autoEat.eating || autoEat.shouldEat()) {
                    if (!wasPausedForEating) {
                        wasPausedForEating = true;
                        stateBeforeEating = currentState;
                        stopMovement();
                        currentState = MiningState.IDLE;
                        if (debugMode.get()) {
                            info("Pausing for AutoEat (hunger/health low)");
                        }
                    }
                    return;
                } else if (wasPausedForEating) {
                    wasPausedForEating = false;
                    currentState = stateBeforeEating;
                    if (debugMode.get()) {
                        info("AutoEat finished, resuming mining");
                    }
                }
            }
        }

        // NEW: Auto Repair with EXP bottles
        if (autoRepair.get() && !isThrowingExp && !isInRTPState()) {
            // Check if enough time has passed since last repair
            long timeSinceLastRepair = System.currentTimeMillis() - lastExpThrowTime;

            if (timeSinceLastRepair >= EXP_THROW_COOLDOWN || lastExpThrowTime == 0) {
                if (needsRepair() && hasExpBottles()) {
                    if (debugMode.get()) {
                        ItemStack mainHand = mc.player.getMainHandStack();
                        double durabilityPercent = SafetyValidator.getToolDurabilityPercent(mainHand);
                        info("Pickaxe durability at " + String.format("%.1f%%%%", durabilityPercent * 100) +
                            " - starting auto repair");
                    }

                    // Save current state
                    stateBeforeExp = currentState;
                    isThrowingExp = true;
                    expThrowTicks = 0;

                    // Stop all movement
                    stopMovement();

                    // Start rotating to look down
                    currentState = MiningState.ROTATING;
                    rotationController.setPreciseLanding(true);
                    rotationController.startRotation(mc.player.getYaw(), 90.0f, () -> {
                        rotationController.setPreciseLanding(false);

                        // Activate EXPThrower module
                        EXPThrower expThrower = Modules.get().get(EXPThrower.class);
                        if (expThrower != null && !expThrower.isActive()) {
                            expThrower.toggle();
                            if (debugMode.get()) {
                                info("EXPThrower activated for repair");
                            }
                        }
                    });
                    return;
                }
            }
        }

        // Handle ongoing EXP throwing
        if (isThrowingExp) {
            handleExpThrowing();
            return; // Skip everything else while throwing EXP
        }

        // Skip stuck detection during mining down or RTP states
        if (!isInRTPState()) {
            SafetyValidator.StuckRecoveryAction recoveryAction = safetyValidator.checkAndHandleStuck(mc.player);

            switch (recoveryAction) {
                case JUMPED:
                    // First attempt - just jumped, let it settle
                    if (debugMode.get()) {
                        info("Stuck detected - attempted jump recovery");
                    }
                    // Continue with current state but pause movement briefly
                    stopMovement();
                    return; // Skip this tick to let jump take effect

                case NEEDS_RESET:
                    // Second attempt - reset module state
                    if (debugMode.get()) {
                        warning("Jump didn't resolve stuck state - resetting module logic");
                    }

                    // Stop all current actions
                    stopMovement();

                    // Clear any ongoing operations
                    currentWaypoint = null;
                    lastHazardDetected = null;
                    pendingDirection = null;
                    isDetouring = false;

                    // Clear visual indicators
                    synchronized (hazardBlocks) {
                        hazardBlocks.clear();
                    }
                    synchronized (waypointBlocks) {
                        waypointBlocks.clear();
                    }

                    // Reset pathfinding components - CHECK FOR NULL AND PRIMARY DIRECTION
                    if (pathfinder != null && pathfinder.getPrimaryDirection() != null) {
                        pathfinder.completeDetour();
                    }

                    // Reset to centering state to recalculate everything
                    currentState = MiningState.CENTERING;
                    blocksMined = 0;
                    scanTicks = 0;

                    // Acknowledge the reset to the safety validator
                    safetyValidator.acknowledgeReset();

                    if (debugMode.get()) {
                        info("Module state reset - starting fresh from centering");
                    }
                    return; // Skip this tick to let reset take effect

                case NONE:
                    // Not stuck, continue normally
                    break;
            }

            // Also add a periodic movement check as a backup
            // This runs every 2 seconds to catch edge cases
            if (tickCounter % 40 == 0 && currentState == MiningState.MINING_PRIMARY) {
                Vec3d currentPos = mc.player.getPos();
                double distanceFromLastCheck = currentPos.distanceTo(lastPos);

                if (distanceFromLastCheck < 0.1) {
                    // Haven't moved much in 2 seconds while mining
                    if (debugMode.get()) {
                        warning("No movement detected while mining - possible stuck on corner");
                    }

                    // Try a quick rotation adjustment to unstick from corners
                    float currentYaw = mc.player.getYaw();
                    Direction currentDir = getCardinalDirection(currentYaw);
                    float targetYaw = directionToYaw(currentDir);

                    // If we're slightly off angle (due to overshoot), correct it
                    float yawDiff = Math.abs(MathHelper.wrapDegrees(currentYaw - targetYaw));
                    if (yawDiff > 2.0f) {
                        if (debugMode.get()) {
                            info("Correcting rotation drift: " + yawDiff + " degrees off");
                        }

                        stopMovement();
                        currentState = MiningState.ROTATING;
                        rotationController.setPreciseLanding(true); // Ensure precise landing for correction
                        rotationController.startRotation(targetYaw, 0.0f, () -> {
                            rotationController.setPreciseLanding(false); // Return to normal after correction
                            currentState = MiningState.SCANNING_PRIMARY;
                        });
                    }
                }
            }
        }

        if (!isInRTPState() && !safetyValidator.canContinue(mc.player, yLevel.get())) {
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

        // Update helpers
        if (parkourHelper != null) {
            parkourHelper.update();
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
                case RTP_INITIATED -> handleRTPInitiated();
                case RTP_WAITING -> handleRTPWaiting();
                case RTP_COOLDOWN -> handleRTPCooldown();
                case RTP_SCANNING_GROUND -> handleRTPScanningGround();
                case MINING_DOWN -> handleMiningDown();
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

    private boolean isInRTPState() {
        return currentState == MiningState.RTP_INITIATED ||
            currentState == MiningState.RTP_WAITING ||
            currentState == MiningState.RTP_COOLDOWN ||
            currentState == MiningState.RTP_SCANNING_GROUND ||
            currentState == MiningState.MINING_DOWN ||
            inRTPRecovery;
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
    private void handleRTPScanningGround() {
        stopMovement();

        // Check if we're already below the target depth
        if (mc.player.getY() <= mineDownTarget.get()) {
            if (debugMode.get()) {
                info("Already below target depth Y=" + Math.round(mc.player.getY()) + " - resuming normal mining");
            }

            // Skip mining down, go straight to normal mining
            hasReachedMineDepth = true;
            rtpAttempts = 0;
            inRTPRecovery = false;

            // Start normal mining sequence
            currentState = MiningState.CENTERING;
            return;
        }

        // First, check if we need to center before mining down
        if (!centeringHelper.isCentering() && !isCenteredOnBlock(mc.player.getBlockPos())) {
            if (debugMode.get()) {
                info("Centering before mining down...");
            }

            if (centeringHelper.startCentering()) {
                // Stay in this state while centering
                return;
            }
        } else if (centeringHelper.isCentering()) {
            // Currently centering, check if done
            if (!centeringHelper.tick()) {
                // Centering complete
                if (debugMode.get()) {
                    info("Centering complete, now checking ground and rotating down");
                }
            } else {
                // Still centering
                return;
            }
        }

        // We're now centered, rotate to look straight down
        float currentPitch = mc.player.getPitch();
        if (Math.abs(currentPitch - 90.0f) > 1.0f) {
            if (debugMode.get()) {
                info("Looking down to scan ground...");
            }

            currentState = MiningState.ROTATING;
            rotationController.setPreciseLanding(true);
            rotationController.startRotation(mc.player.getYaw(), 90.0f, () -> {
                rotationController.setPreciseLanding(false);
                currentState = MiningState.RTP_SCANNING_GROUND;
            });
            return;
        }

        // Scan ground below
        if (scanGroundBelow()) {
            // Ground is safe, start mining down
            if (debugMode.get()) {
                info("Ground is safe - starting to mine down to Y=" + mineDownTarget.get());
            }

            hasReachedMineDepth = false;
            mineDownScanTicks = 0;
            miningDownInitiated = false; // RESET FLAG
            miningDownToggleTicks = 0;
            currentState = MiningState.MINING_DOWN;

            // DON'T start mining here - let handleMiningDown do it with proper toggle
        } else {
            // Ground has fluids - ALWAYS try RTP if enabled
            if (debugMode.get()) {
                warning("Fluids detected below!");
            }

            // Check if RTP is enabled
            if (rtpWhenStuck.get()) {
                if (debugMode.get()) {
                    if (rtpAttempts == 0) {
                        info("Surface has fluids below - initiating RTP to find safe ground");
                    } else {
                        warning("RTP landed on fluids - trying again (attempt " + (rtpAttempts + 1) + "/5)");
                    }
                }

                rtpAttempts++;
                if (rtpAttempts < 5) {
                    initiateRTP();
                } else {
                    error("Failed to find safe ground after 5 RTP attempts");
                    toggle();
                }
            } else {
                // RTP not enabled, can't escape fluids
                error("Cannot mine down - fluids detected below! Enable 'RTP When Stuck' or try starting from a different location.");
                toggle();
            }
        }
    }

    private void handleMiningDown() {
        // Check if we've reached target depth
        if (mc.player.getY() <= mineDownTarget.get()) {
            if (!hasReachedMineDepth) {
                if (debugMode.get()) {
                    info("Reached target depth Y=" + Math.round(mc.player.getY()) + " - starting normal mining sequence");
                }

                hasReachedMineDepth = true;

                // IMPORTANT: Stop ALL movement including sneak BEFORE transitioning
                stopMovement();
                setPressed(mc.options.sneakKey, false); // EXPLICITLY RELEASE SNEAK

                // Clear RTP recovery state
                rtpAttempts = 0;
                inRTPRecovery = false;
                rtpCommandSent = false;
                miningDownInitiated = false; // Reset flag

                // Now go to CENTERING state with no keys pressed
                currentState = MiningState.CENTERING;

                if (debugMode.get()) {
                    info("Entering centering state to begin normal mining");
                }
            }
            return;
        }

        // FIXED: Toggle keys on first entry or periodically to ensure they register
        if (!miningDownInitiated) {
            // First time entering mining down - toggle keys to ensure registration
            if (debugMode.get()) {
                info("Initiating mining down - toggling keys to ensure registration");
            }

            // First, make sure all keys are released
            stopMovement();
            setPressed(mc.options.sneakKey, false);
            setPressed(mc.options.attackKey, false);

            // Small delay then press keys (will happen next tick)
            miningDownInitiated = true;
            miningDownToggleTicks = 0;
            return;
        }

        // Increment toggle counter
        miningDownToggleTicks++;

        // Re-toggle keys every 20 ticks (1 second) to ensure they stay registered
        if (miningDownToggleTicks % 20 == 0) {
            // Quick toggle to refresh key state
            setPressed(mc.options.attackKey, false);
            setPressed(mc.options.sneakKey, false);

            // Will re-press on next line
            if (debugMode.get() && miningDownToggleTicks % 100 == 0) {
                System.out.println("Refreshing mining keys (tick " + miningDownToggleTicks + ")");
            }
        }

        // Continue mining down with periodic safety checks
        mineDownScanTicks++;

        if (mineDownScanTicks >= MINE_DOWN_SCAN_INTERVAL) {
            mineDownScanTicks = 0;

            // Quick scan for fluids while mining down
            if (!scanGroundBelow()) {
                // IMMEDIATELY STOP MINING AND SNEAKING
                stopMovement();
                setPressed(mc.options.sneakKey, false); // Release sneak immediately

                if (debugMode.get()) {
                    error("Fluids detected while mining down - stopping immediately!");
                }

                // ALWAYS try RTP if enabled and fluids detected
                if (rtpWhenStuck.get()) {
                    rtpAttempts++;
                    if (rtpAttempts < 5) {
                        if (debugMode.get()) {
                            info("Attempting RTP to escape fluids (attempt " + rtpAttempts + "/5)");
                        }
                        initiateRTP();
                    } else {
                        error("Too many fluid encounters - stopping module");
                        inRTPRecovery = false;
                        miningDownInitiated = false;
                        toggle();
                    }
                } else {
                    // RTP not enabled, can't escape
                    error("Hit fluids while mining down - cannot continue without RTP enabled");
                    inRTPRecovery = false;
                    miningDownInitiated = false;
                    toggle();
                }

                return; // Exit immediately, don't continue mining
            }
        }

        // Always ensure keys are pressed for mining down
        setPressed(mc.options.attackKey, true);
        setPressed(mc.options.sneakKey, true);

        // Extra insurance - if we're not moving down after 2 seconds, try toggling again
        if (miningDownToggleTicks > 40 && miningDownToggleTicks % 40 == 0) {
            Vec3d currentPos = mc.player.getPos();
            if (lastPos != null && Math.abs(currentPos.y - lastPos.y) < 0.5) {
                if (debugMode.get()) {
                    warning("Not moving down - retoggling mining keys");
                }

                // Force retoggle
                stopMovement();
                setPressed(mc.options.sneakKey, false);
                setPressed(mc.options.attackKey, false);
                miningDownToggleTicks = 0;
            }
            lastPos = currentPos;
        }
    }

    private boolean scanGroundBelow() {
        World world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int scanRadius = groundScanSize.get() / 2;

        // Scan area below player (check up to 10 blocks down)
        for (int depth = 1; depth <= 10; depth++) {
            for (int x = -scanRadius; x <= scanRadius; x++) {
                for (int z = -scanRadius; z <= scanRadius; z++) {
                    BlockPos checkPos = playerPos.add(x, -depth, z);
                    BlockState state = world.getBlockState(checkPos);

                    // Check for fluids
                    if (state.getFluidState().getFluid() == Fluids.LAVA ||
                        state.getFluidState().getFluid() == Fluids.FLOWING_LAVA ||
                        state.getFluidState().getFluid() == Fluids.WATER ||
                        state.getFluidState().getFluid() == Fluids.FLOWING_WATER) {

                        if (debugMode.get()) {
                            String fluidType = state.getFluidState().getFluid() == Fluids.LAVA ? "Lava" : "Water";
                            System.out.println("Found " + fluidType + " at depth " + depth + ", offset X=" + x + " Z=" + z);
                        }
                        return false;
                    }
                }
            }
        }

        return true; // No fluids found
    }

    private void initiateRTP() {
        if (mc.player == null) return;

        // Set recovery flag BEFORE doing anything else
        inRTPRecovery = true;

        // Check cooldown
        long timeSinceLastRtp = System.currentTimeMillis() - lastRtpTime;
        if (lastRtpTime > 0 && timeSinceLastRtp < (RTP_COOLDOWN_SECONDS * 1000L)) {
            int remainingSeconds = (int)((RTP_COOLDOWN_SECONDS * 1000L - timeSinceLastRtp) / 1000L);

            if (debugMode.get()) {
                info("RTP on cooldown for " + remainingSeconds + " more seconds");
            }

            currentState = MiningState.RTP_COOLDOWN;
            rtpWaitTicks = (RTP_COOLDOWN_SECONDS - remainingSeconds) * 20;
            return;
        }

        // Store current position
        preRtpPos = mc.player.getBlockPos();

        // Send RTP command
        String command = "/rtp " + rtpRegion.get().getCommand();
        ChatUtils.sendPlayerMsg(command);

        if (debugMode.get()) {
            info("Sending RTP command to " + rtpRegion.get().toString() + " (attempt #" + (rtpAttempts + 1) + ")");
        }

        // Update state
        currentState = MiningState.RTP_INITIATED;
        rtpWaitTicks = 0;
        rtpCommandSent = true;
    }

    private void handleMiningPrimary() {

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
            isDetouring = true;
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
            // Check if this is a "no paths found" situation (null direction returned)
            // This happens when the pathfinder can't find any valid direction
            if (plan.reason != null && plan.reason.contains("No valid paths")) {
                // Check if RTP is enabled for stuck situations
                if (rtpWhenStuck.get()) {
                    if (debugMode.get()) {
                        warning("Completely blocked with no valid paths - initiating RTP recovery");
                    }

                    mc.getToastManager().add(new MeteorToast(
                        Items.ENDER_PEARL,
                        "No Paths Found",
                        "Initiating RTP Recovery"
                    ));

                    rtpAttempts = 0;
                    initiateRTP();
                } else {
                    // RTP not enabled, stop the module
                    currentState = MiningState.STOPPED;
                    if (debugMode.get()) {
                        error("No valid paths found and RTP is disabled - stopping");
                    }
                }
            } else {
                // Some other reason for no path
                currentState = MiningState.STOPPED;
            }
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

    private boolean needsRepair() {
        if (!autoRepair.get()) return false;

        ItemStack mainHand = mc.player.getMainHandStack();
        if (mainHand == null || mainHand.isEmpty() || !isTool(mainHand)) {
            return false;
        }

        double durabilityPercent = SafetyValidator.getToolDurabilityPercent(mainHand);
        return durabilityPercent <= (repairThreshold.get() / 100.0);
    }

    private boolean hasExpBottles() {
        FindItemResult exp = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        return exp.found();
    }

    private void handleExpThrowing() {
        expThrowTicks++;

        // First, ensure we're looking down
        float currentPitch = mc.player.getPitch();
        if (Math.abs(currentPitch - 90.0f) > 1.0f && expThrowTicks < 10) {
            // Still need to rotate
            return;
        }

        // Check if we're done throwing
        if (expThrowTicks >= (repairDuration.get() * 20)) { // Convert seconds to ticks
            if (debugMode.get()) {
                info("Finished throwing EXP bottles, resuming mining");
            }

            // Stop EXPThrower module
            EXPThrower expThrower = Modules.get().get(EXPThrower.class);
            if (expThrower != null && expThrower.isActive()) {
                expThrower.toggle();
            }

            // Resume previous state
            isThrowingExp = false;
            currentState = stateBeforeExp;
            expThrowTicks = 0;
            lastExpThrowTime = System.currentTimeMillis();

            // Rotate back to normal pitch
            currentState = MiningState.ROTATING;
            rotationController.startRotation(mc.player.getYaw(), 0.0f, () -> {
                currentState = stateBeforeExp;
            });
        }
    }

    private void handleFollowingDetour() {
        // Check if we have a current waypoint
        if (currentWaypoint == null) {
            // Get next waypoint
            currentWaypoint = pathfinder.getNextWaypoint();
            if (currentWaypoint == null) {
                // Detour complete
                pathfinder.completeDetour();
                isDetouring = false;
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
            // This shouldn't happen anymore since we handle RTP in handleCalculatingDetour
            // But keep as a safety fallback
            if (debugMode.get()) {
                warning("handleChangingDirection called with null pendingDirection - this shouldn't happen");
            }

            // Try RTP as a last resort if enabled
            if (rtpWhenStuck.get()) {
                if (debugMode.get()) {
                    warning("Initiating RTP recovery as fallback");
                }

                mc.getToastManager().add(new MeteorToast(
                    Items.ENDER_PEARL,
                    "No Paths Found",
                    "Initiating RTP Recovery"
                ));

                rtpAttempts = 0;
                initiateRTP();
            } else {
                currentState = MiningState.STOPPED;
            }
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
            "DonutClient BStar Found spawners" :
            "DonutClient BStar Found base";

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
