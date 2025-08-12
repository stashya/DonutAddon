package com.DonutAddon.addon;

import com.DonutAddon.addon.hud.ImageHud;
import com.DonutAddon.addon.hud.MeteorImageHud;
import com.DonutAddon.addon.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class DonutAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("DonutAddon");
    public static final HudGroup HUD_GROUP = new HudGroup("DonutAddon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing DonutAddon");

        // Modules
        Modules.get().add(new ClusterFinder());
        Modules.get().add(new DeepslateESP());
        Modules.get().add(new DripstoneESP());
        Modules.get().add(new StoneESP());
        Modules.get().add(new AutoRocket());
        Modules.get().add(new AutoRTP());
        Modules.get().add(new HoleTunnelStairsESP());
        Modules.get().add(new CoveredHole());
        Modules.get().add(new PillagerESP());
        Modules.get().add(new WanderingTraderESP());
        Modules.get().add(new BStar());

        // HUD
        Hud.get().register(MeteorImageHud.INFO);

        // Check if we should add watermark immediately
        if (shouldAddWatermark()) {
            LOG.info("Adding DonutClient watermark on initialization");
            Hud.get().add(MeteorImageHud.WATERMARK, -4, 4, XAnchor.Right, YAnchor.Top);
        }
    }

    private boolean shouldAddWatermark() {
        // Check if the watermark already exists
        for (HudElement element : Hud.get()) {
            if (element.info != null && element.info.name.equals("donut-image")) {
                // Watermark already exists, ensure it's configured properly
                if (element instanceof ImageHud imageHud) {
                    imageHud.configureAsWatermark("/assets/donutaddon/textures/logo1.png", 120, 0.5);
                }
                return false; // Don't add duplicate
            }
        }
        return true; // Add watermark
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.DonutAddon.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
