package io.github.stashya.donutaddon.modules;

import io.github.stashya.donutaddon.DonutAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoRTP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Region> region = sgGeneral.add(new EnumSetting.Builder<Region>()
            .name("region")
            .description("The region to RTP to.")
            .defaultValue(Region.EU_CENTRAL)
            .build()
    );

    public enum Region {
        EU_CENTRAL("Eu Central", "eu central"),
        EU_WEST("Eu West", "eu west"),
        NA_EAST("Na East", "east"),
        NA_WEST("Na West", "west"),
        OCEANIA("Oceania", "oceania"),
        ASIA("Asia", "asia");

        private final String name;
        private final String command;

        Region(String name, String command) {
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

    private enum State {
        IDLE,
        COMMAND_SENT,
        WAITING_FOR_SPAWN_TP,
        WAITING_FOR_FINAL_TP,
        TELEPORT_COMPLETE,
        COOLDOWN
    }

    private State state = State.IDLE;
    private int tickTimer = 0;
    private BlockPos teleportPos = null;
    private int attempts = 0;
    private boolean canRtp = true;
    private long lastRtpTime = 0;
    private int teleportCount = 0;

    // Hard-coded values
    private static final int COORDINATE_THRESHOLD = 175000; // 175k
    private static final int RTP_COOLDOWN = 16; // 16 seconds
    private static final BlockPos SPAWN_POS = new BlockPos(8, 63, 8);
    private static final int SPAWN_RADIUS = 20;

    // Pattern to match "You can't rtp for another Xs" message
    private static final Pattern RTP_COOLDOWN_PATTERN = Pattern.compile("You can't rtp for another (\\d+)s");

    public AutoRTP() {
        super(DonutAddon.CATEGORY, "auto-rtp", "Automatically RTPs until good coordinates are found.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        tickTimer = 0;
        teleportPos = null;
        attempts = 0;
        canRtp = true;
        teleportCount = 0;

        if (mc.player == null) return;

        // Check if we can RTP based on time
        long timeSinceLastRtp = System.currentTimeMillis() - lastRtpTime;
        if (lastRtpTime > 0 && timeSinceLastRtp < (RTP_COOLDOWN * 1000L)) {
            int remainingSeconds = (int) ((RTP_COOLDOWN * 1000L - timeSinceLastRtp) / 1000L);
            state = State.COOLDOWN;
            tickTimer = (RTP_COOLDOWN - remainingSeconds) * 20;
        } else {
            // Send first RTP immediately if cooldown is clear
            sendRTP();
        }
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE;
        info("AutoRTP stopped after %d attempts.", attempts);
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String message = event.getMessage().getString();

        // Check for RTP cooldown message
        Matcher cooldownMatcher = RTP_COOLDOWN_PATTERN.matcher(message);
        if (!cooldownMatcher.find()) return;
        int remainingSeconds = Integer.parseInt(cooldownMatcher.group(1));

        // Sync our cooldown with server
        state = State.COOLDOWN;
        tickTimer = (RTP_COOLDOWN - remainingSeconds) * 20;
        canRtp = false;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket)) return;
        if (mc.player == null) return;

        // Always process teleport packets to stay in sync with server
        // Schedule position check with a small delay to ensure position is updated
        new Thread(() -> {
            try {
                Thread.sleep(100); // 100ms delay for position to update
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            MeteorExecutor.execute(() -> {
                BlockPos newPos = mc.player.getBlockPos();

                // If we're in cooldown but receive a teleport, it's likely from a previous RTP
                if (state == State.COOLDOWN || state == State.IDLE) {
                    teleportPos = newPos;
                    lastRtpTime = System.currentTimeMillis();

                    // Check if this completed teleport has good coordinates
                    if (!isNearSpawn(newPos) && checkCoordinates(newPos)) {
                        mc.getToastManager().add(new MeteorToast(
                                Items.DRAGON_EGG,
                                "Found Good Coordinates",
                                "Good Luck!"
                        ));
                        toggle();
                        return;
                    }

                    // Reset cooldown timer since we just teleported
                    state = State.COOLDOWN;
                    tickTimer = 0;
                    canRtp = false;
                    return;
                }

                if (state == State.WAITING_FOR_SPAWN_TP || state == State.WAITING_FOR_FINAL_TP || state == State.COMMAND_SENT) {
                    teleportCount++;
                    info("State: %s, Teleport #%d at X=%d, Z=%d", state.toString(), teleportCount, newPos.getX(), newPos.getZ());

                    // Check if this is spawn teleport
                    if (isNearSpawn(newPos)) {
                        info("Detected spawn teleport");
                        state = State.WAITING_FOR_FINAL_TP;
                        tickTimer = 0;
                        teleportCount = 1; // Reset count as spawn is first teleport
                    } else {
                        // This is the final teleport
                        teleportPos = newPos;

                        info("RTP completed (attempt #%d)", attempts);

                        state = State.TELEPORT_COMPLETE;
                        tickTimer = 0;
                        teleportCount = 0;

                        // Check if coordinates are good
                        if (checkCoordinates(teleportPos)) {
                            mc.getToastManager().add(new MeteorToast(
                                    Items.DRAGON_EGG,
                                    "Found Good Coordinates",
                                    "Good Luck!"
                            ));
                            toggle();
                        } else {
                            info("(highlight)Bad coords(default), trying again. (highlight)Stay still(default).");
                            state = State.COOLDOWN;
                            canRtp = false;
                        }
                    }
                }
            });
        }).start();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        tickTimer++;

        switch (state) {
            case IDLE:
                // Waiting to start or between attempts
                break;

            case COMMAND_SENT:
                // Command was just sent, wait a bit before checking for teleport
                if (tickTimer > 40) { // 2 second wait for first packet
                    state = State.WAITING_FOR_SPAWN_TP;
                }
                break;

            case WAITING_FOR_SPAWN_TP:
            case WAITING_FOR_FINAL_TP:
                // Waiting for teleport packets
                if (tickTimer > 200) { // 10 second timeout
                    state = State.COOLDOWN;
                    tickTimer = 0;
                    canRtp = false;
                    teleportCount = 0;
                }
                break;

            case TELEPORT_COMPLETE:
                // Teleport completed, already checked coordinates
                state = State.COOLDOWN;
                tickTimer = 0;
                break;

            case COOLDOWN:
                int cooldownTicks = RTP_COOLDOWN * 20;

                if (tickTimer >= cooldownTicks) {
                    canRtp = true;
                    sendRTP();
                }
                break;
        }
    }

    private void sendRTP() {
        if (mc.player == null) return;
        if (!canRtp) return;

        // Check if enough time has passed since last RTP
        long timeSinceLastRtp = System.currentTimeMillis() - lastRtpTime;
        if (lastRtpTime > 0 && timeSinceLastRtp < (RTP_COOLDOWN * 1000L - 1000L)) {
            state = State.COOLDOWN;
            tickTimer = (int) (timeSinceLastRtp / 1000) * 20;
            return;
        }

        attempts++;
        lastRtpTime = System.currentTimeMillis();
        teleportCount = 0;

        String command = "/rtp " + region.get().getCommand();
        ChatUtils.sendPlayerMsg(command);

        info("Sending RTP command (attempt #%d)", attempts);

        state = State.COMMAND_SENT;
        tickTimer = 0;
    }

    private boolean checkCoordinates(BlockPos pos) {
        if (pos == null) return false;

        int x = pos.getX();
        int z = pos.getZ();

        // Check if X is beyond 175k in either positive or negative direction
        boolean goodX = x > COORDINATE_THRESHOLD || x < -COORDINATE_THRESHOLD;

        // Check if Z is beyond 175k in either positive or negative direction
        boolean goodZ = z > COORDINATE_THRESHOLD || z < -COORDINATE_THRESHOLD;

        // Temporary logging to debug
        info("Checking coords: X=%d (good=%b), Z=%d (good=%b)", x, goodX, z, goodZ);

        return goodX || goodZ;
    }

    private boolean isNearSpawn(BlockPos pos) {
        int dx = Math.abs(pos.getX() - SPAWN_POS.getX());
        if (dx > SPAWN_RADIUS) return false;
        int dz = Math.abs(pos.getZ() - SPAWN_POS.getZ());
        return dz <= SPAWN_RADIUS;
    }
}
