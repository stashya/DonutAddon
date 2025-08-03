package com.DonutAddon.addon.modules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.DonutAddon.addon.DonutAddon;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;

public class AutoSpawnerBreakerBaritone extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgWhitelist;
    private final SettingGroup sgwebhook;
    private final Setting<Boolean> webhook;
    private final Setting<String> webhookUrl;
    private final Setting<Boolean> selfPing;
    private final Setting<String> discordId;
    private final Setting<Integer> spawnerRange;
    private final Setting<Integer> delaySeconds;
    private final Setting<Boolean> disableAutoReconnect;
    private final Setting<Boolean> enableWhitelist;
    private final Setting<List<String>> whitelistPlayers;
    private State currentState;
    private String detectedPlayer;
    private long detectionTime;
    private boolean spawnersMinedSuccessfully;
    private boolean itemsDepositedSuccessfully;
    private int tickCounter;
    private boolean chestOpened;
    private int transferDelayCounter;
    private int lastProcessedSlot;
    private boolean sneaking;
    private BlockPos currentTarget;
    private int recheckDelay;
    private int confirmDelay;
    private boolean waiting;



    public AutoSpawnerBreakerBaritone() {
        super(DonutAddon.CATEGORY, "Spawner Protect", "Breaks spawners, deposits in echest, and disconnects");
        this.sgGeneral = this.settings.getDefaultGroup();
        this.sgWhitelist = this.settings.createGroup("Whitelist");
        this.sgwebhook = this.settings.createGroup("Webhook");

        this.webhook = this.sgwebhook.add(new BoolSetting.Builder()
            .name("webhook")
            .description("Enable webhook notifications")
            .defaultValue(false)
            .build());

        this.webhookUrl = this.sgwebhook.add(new StringSetting.Builder()
            .name("webhook-url")
            .description("Discord webhook URL for notifications")
            .defaultValue("")
            .visible(webhook::get)
            .build());

        this.selfPing = this.sgwebhook.add(new BoolSetting.Builder()
            .name("Self Ping")
            .description("Ping yourself in the webhook message")
            .defaultValue(false)
            .visible(webhook::get)
            .build());

        this.discordId = this.sgGeneral.add(new StringSetting.Builder()
            .name("Discord ID")
            .description("Your Discord user ID for pinging")
            .defaultValue("")
            .visible(() -> webhook.get() && selfPing.get())
            .build());

        this.spawnerRange = this.sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range")
            .description("Range to check for remaining spawners")
            .defaultValue(16)
            .min(1)
            .max(50)
            .sliderMax(50)
            .build());

        this.delaySeconds = this.sgGeneral.add(new IntSetting.Builder()
            .name("recheck-delay-seconds")
            .description("Delay in seconds before rechecking for spawners")
            .defaultValue(1)
            .min(1)
            .sliderMax(10)
            .build());

        this.disableAutoReconnect = this.sgGeneral.add(new BoolSetting.Builder()
            .name("Disable AutoReconnect")
            .description("Disables AutoReconnect")
            .defaultValue(true)
            .build());

        this.enableWhitelist = this.sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist")
            .description("Enable player whitelist (whitelisted players won't trigger protection)")
            .defaultValue(false)
            .build());

        this.whitelistPlayers = this.sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players")
            .description("List of player names to ignore")
            .defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get)
            .build());

        this.currentState = State.IDLE;
        this.detectedPlayer = "";
        this.detectionTime = 0L;
        this.spawnersMinedSuccessfully = false;
        this.itemsDepositedSuccessfully = false;
        this.tickCounter = 0;
        this.chestOpened = false;
        this.transferDelayCounter = 0;
        this.lastProcessedSlot = -1;
        this.sneaking = false;
        this.currentTarget = null;
        this.recheckDelay = 0;
        this.confirmDelay = 0;
        this.waiting = false;
    }

    public void onActivate() {
        this.currentState = State.IDLE;
        this.detectedPlayer = "";
        this.detectionTime = 0L;
        this.spawnersMinedSuccessfully = false;
        this.itemsDepositedSuccessfully = false;
        this.tickCounter = 0;
        this.chestOpened = false;
        this.transferDelayCounter = 0;
        this.lastProcessedSlot = -1;
        this.sneaking = false;
        this.currentTarget = null;
        this.recheckDelay = 0;
        this.confirmDelay = 0;
        this.waiting = false;
        ChatUtils.sendPlayerMsg("#set legitMine true");
        ChatUtils.sendPlayerMsg("#set smoothLook true");
        ChatUtils.sendPlayerMsg("#set antiCheatCompatibility true");
        ChatUtils.sendPlayerMsg("#freelook false");
        ChatUtils.sendPlayerMsg("#legitMineIncludeDiagonals true");
        ChatUtils.sendPlayerMsg("#smoothLookTicks 10");
        this.info("DonutClientPathing activated - monitoring for players...");
        ChatUtils.warning("Make sure to have an empty inventory with only a silk touch pickaxe and an ender chest nearby!");
    }

    private void toggleModule(Class<? extends Module> moduleClass, boolean disable) {
        Module module = Modules.get().get(moduleClass);
        if (module != null) {
            if (disable && module.isActive()) {
                module.toggle();
            } else if (!disable && module.isActive()) {
                module.toggle();
            }
        }

    }

    @EventHandler
    private void onTick(Pre event) {
        if (this.mc.player != null && this.mc.world != null) {
            ++this.tickCounter;
            if (this.transferDelayCounter > 0) {
                --this.transferDelayCounter;
            } else {
                switch(this.currentState.ordinal()) {
                    case 0:
                        this.checkForPlayers();
                        break;
                    case 1:
                        this.handleGoingToSpawners();
                        break;
                    case 2:
                        this.handleGoingToChest();
                        break;
                    case 3:
                        this.handleDepositingItems();
                        break;
                    case 4:
                        this.handleDisconnecting();
                }

                this.toggleModule(AutoReconnect.class, (Boolean)this.disableAutoReconnect.get());
            }
        }
    }

    private void checkForPlayers() {
        Iterator var1 = this.mc.world.getPlayers().iterator();

        while(var1.hasNext()) {
            PlayerEntity player = (PlayerEntity)var1.next();
            if (player != this.mc.player && player instanceof OtherClientPlayerEntity) {
                String playerName = player.getName().getString();
                if (!(Boolean)this.enableWhitelist.get() || !this.isPlayerWhitelisted(playerName)) {
                    this.detectedPlayer = playerName;
                    this.detectionTime = System.currentTimeMillis();
                    this.info("DonutClientPathing: Player detected - " + this.detectedPlayer);
                    this.currentState = State.GOING_TO_SPAWNERS;
                    this.info("Player detected! Starting protection sequence...");
                    if (!this.sneaking) {
                        this.mc.player.setSneaking(true);
                        this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
                        this.sneaking = true;
                    }
                    break;
                }
            }
        }

    }

    private boolean isPlayerWhitelisted(String playerName) {
        if ((Boolean)this.enableWhitelist.get() && !((List)this.whitelistPlayers.get()).isEmpty()) {
            Iterator var2 = ((List)this.whitelistPlayers.get()).iterator();

            String whitelistedName;
            do {
                if (!var2.hasNext()) {
                    return false;
                }

                whitelistedName = (String)var2.next();
            } while(!whitelistedName.equalsIgnoreCase(playerName));

            return true;
        } else {
            return false;
        }
    }

    private void handleGoingToSpawners() {
        if (!this.sneaking) {
            this.mc.player.setSneaking(true);
            this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            this.sneaking = true;
        }

        if (this.currentTarget == null) {
            this.currentTarget = this.findNearestSpawner();
            if (this.currentTarget == null && !this.waiting) {
                this.waiting = true;
                this.recheckDelay = 0;
                this.confirmDelay = 0;
                this.info("No more spawners found, waiting to confirm...");
            }
        } else {
            this.lookAtBlock(this.currentTarget);
            this.mc.interactionManager.updateBlockBreakingProgress(this.currentTarget, Direction.UP);
            mc.options.attackKey.setPressed(true);
            if (this.mc.world.getBlockState(this.currentTarget).isAir()) {
                this.info("Spawner at " + String.valueOf(this.currentTarget) + " broken! Looking for next spawner...");
                this.currentTarget = null;
                mc.options.attackKey.setPressed(false);
                this.transferDelayCounter = 5;
            }
        }

        if (this.waiting) {
            ++this.recheckDelay;
            if (this.recheckDelay == (Integer)this.delaySeconds.get() * 20) {
                BlockPos foundSpawner = this.findNearestSpawner();
                if (foundSpawner != null) {
                    this.waiting = false;
                    this.currentTarget = foundSpawner;
                    this.info("Found additional spawner at " + String.valueOf(foundSpawner));
                    return;
                }
            }

            if (this.recheckDelay > (Integer)this.delaySeconds.get() * 20) {
                ++this.confirmDelay;
                if (this.confirmDelay >= 5) {
                    mc.options.attackKey.setPressed(false);
                    this.spawnersMinedSuccessfully = true;
                    if (this.sneaking && this.mc.player.isSneaking()) {
                        this.mc.player.setSneaking(false);
                        this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
                        this.sneaking = false;
                    }

                    this.currentState = State.GOING_TO_CHEST;
                    this.info("All spawners mined successfully. Going to ender chest...");
                    ChatUtils.sendPlayerMsg("#goto ender_chest");
                    this.tickCounter = 0;
                }
            }
        }

    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = this.mc.player.getBlockPos();
        BlockPos nearestSpawner = null;
        double nearestDistance = Double.MAX_VALUE;
        Iterator var5 = BlockPos.iterate(playerPos.add(-(Integer)this.spawnerRange.get(), -(Integer)this.spawnerRange.get(), -(Integer)this.spawnerRange.get()), playerPos.add((Integer)this.spawnerRange.get(), (Integer)this.spawnerRange.get(), (Integer)this.spawnerRange.get())).iterator();

        while(var5.hasNext()) {
            BlockPos pos = (BlockPos)var5.next();
            if (this.mc.world.getBlockState(pos).getBlock() == Blocks.SPAWNER) {
                double distance = pos.getSquaredDistance(this.mc.player.getPos());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSpawner = pos.toImmutable();
                }
            }
        }

        if (nearestSpawner != null) {
            this.info("Found spawner at " + String.valueOf(nearestSpawner) + " (distance: " + Math.sqrt(nearestDistance) + ")");
        }

        return nearestSpawner;
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3d targetPos = Vec3d.ofCenter(pos);
        Vec3d playerPos = this.mc.player.getEyePos();
        Vec3d direction = targetPos.subtract(playerPos).normalize();
        double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double pitch = Math.toDegrees(-Math.asin(direction.y));
        this.mc.player.setYaw((float)yaw);
        this.mc.player.setPitch((float)pitch);
    }

    private void handleGoingToChest() {
        boolean nearEnderChest = false;
        BlockPos playerPos = this.mc.player.getBlockPos();

        for(int x = -3; x <= 3; ++x) {
            for(int y = -3; y <= 3; ++y) {
                for(int z = -3; z <= 3; ++z) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (this.mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                        nearEnderChest = true;
                        break;
                    }
                }
            }
        }

        if (nearEnderChest) {
            this.currentState = State.DEPOSITING_ITEMS;
            this.tickCounter = 0;
            this.info("Reached ender chest area. Opening and depositing items...");
        }

        if (this.tickCounter > 600) {
            ChatUtils.error("Timed out trying to reach ender chest!");
            this.currentState = State.DISCONNECTING;
        }

    }

    private void handleDepositingItems() {
        ScreenHandler var2 = this.mc.player.currentScreenHandler;
        if (var2 instanceof GenericContainerScreenHandler) {
            GenericContainerScreenHandler handler = (GenericContainerScreenHandler)var2;
            if (!this.chestOpened) {
                this.chestOpened = true;
                this.lastProcessedSlot = -1;
                this.info("Ender chest opened, starting item transfer...");
            }

            if (!this.hasItemsToDeposit()) {
                this.itemsDepositedSuccessfully = true;
                this.info("All items deposited successfully!");
                this.mc.player.closeHandledScreen();
                this.transferDelayCounter = 10;
                this.currentState = State.DISCONNECTING;
                return;
            }

            this.transferItemsToChest(handler);
        } else if (this.tickCounter % 20 == 0) {
            ChatUtils.sendPlayerMsg("#goto ender_chest");
        }

        if (this.tickCounter > 900) {
            ChatUtils.error("Timed out depositing items!");
            this.currentState = State.DISCONNECTING;
        }

    }

    private boolean hasItemsToDeposit() {
        for(int i = 0; i < 36; ++i) {
            ItemStack stack = this.mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() != Items.DIAMOND_PICKAXE) {
                return true;
            }
        }

        return false;
    }

    private void transferItemsToChest(GenericContainerScreenHandler handler) {
        int totalSlots = handler.slots.size();
        int chestSlots = totalSlots - 36;
        int playerInventoryStart = chestSlots;
        int startSlot = Math.max(this.lastProcessedSlot + 1, chestSlots);

        for(int i = 0; i < 36; ++i) {
            int slotId = playerInventoryStart + (startSlot - playerInventoryStart + i) % 36;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && stack.getItem() != Items.DIAMOND_PICKAXE) {
                this.info("Transferring item from slot " + slotId + ": " + stack.getItem().toString());
                this.mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, this.mc.player);
                this.lastProcessedSlot = slotId;
                this.transferDelayCounter = 2;
                return;
            }
        }

        if (this.lastProcessedSlot >= playerInventoryStart) {
            this.lastProcessedSlot = playerInventoryStart - 1;
            this.transferDelayCounter = 3;
        }

    }

    private void handleDisconnecting() {
        this.sendWebhookNotification();
        this.info("DonutClientPathing: Player detected - " + this.detectedPlayer);
        if (this.mc.world != null) {
            this.mc.world.disconnect();
        }

        this.info("Disconnected due to player detection.");
        this.toggle();
    }

    private void sendWebhookNotification() {
        if ((Boolean)this.webhook.get() && !((String)this.webhookUrl.get()).isEmpty()) {
            long discordTimestamp = this.detectionTime / 1000L;
            String messageContent = "";
            if ((Boolean)this.selfPing.get() && !((String)this.discordId.get()).trim().isEmpty()) {
                messageContent = String.format("<@%s>", ((String)this.discordId.get()).trim());
            }

            String embedJson = String.format("{\n    \"username\": \"Donut Webhook\",\n    \"avatar_url\": \"https://imgur.com/a/xTvpHzq\",\n    \"content\": \"%s\",\n    \"embeds\": [{\n        \"title\": \"SpawnerProtect Alert\",\n        \"description\": \"**Player Detected:** %s\\n**Detection Time:** <t:%d:R>\\n**Spawners Mined:** %s\\n**Items Deposited:** %s\\n**Disconnected:** Yes\",\n        \"color\": 16766720,\n        \"timestamp\": \"%s\",\n        \"footer\": {\n            \"text\": \"Sent by DonutClient\"\n        }\n    }]\n}", messageContent.replace("\"", "\\\""), this.detectedPlayer, discordTimestamp, this.spawnersMinedSuccessfully ? "✅ Success" : "❌ Failed", this.itemsDepositedSuccessfully ? "✅ Success" : "❌ Failed", Instant.now().toString());
            (new Thread(() -> {
                try {
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create((String)this.webhookUrl.get())).header("Content-Type", "application/json").POST(BodyPublishers.ofString(embedJson)).build();
                    client.send(request, BodyHandlers.ofString());
                    this.info("Webhook notification sent successfully!");
                } catch (Exception var4) {
                    ChatUtils.error("Failed to send webhook notification: " + var4.getMessage());
                }

            })).start();
        } else {
            this.info("Webhook disabled or URL not configured.");
        }
    }

    public void onDeactivate() {
        mc.options.attackKey.setPressed(false);
        if (this.sneaking && this.mc.player != null && this.mc.player.isSneaking()) {
            this.mc.player.setSneaking(false);
            this.mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(this.mc.player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
        }

        ChatUtils.sendPlayerMsg("#stop");
    }

    private static enum State {
        IDLE,
        GOING_TO_SPAWNERS,
        GOING_TO_CHEST,
        DEPOSITING_ITEMS,
        DISCONNECTING;
    }
}
