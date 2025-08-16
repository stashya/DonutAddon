/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.DonutAddon.addon.hud;

import com.DonutAddon.addon.DonutAddon;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;

public class MeteorImageHud {
    // Use your addon's HUD group and unique name
    public static final HudElementInfo<ImageHud> INFO = new HudElementInfo<>(
        DonutAddon.HUD_GROUP,  // Use your addon's HUD group
        "donut-image",         // Unique name to match in the mixin
        "Displays a static image on the HUD.",
        MeteorImageHud::create
    );

    public static final HudElementInfo<ImageHud>.Preset WATERMARK;

    static {
        WATERMARK = INFO.addPreset("DonutClient Watermark", imageHud -> {
            // Configure for DonutClient watermark using the public method
            imageHud.configureAsWatermark("/assets/donutaddon/textures/logo1.png", 120, 0.3);
        });
    }

    private static ImageHud create() {
        return new ImageHud(INFO);
    }
}
