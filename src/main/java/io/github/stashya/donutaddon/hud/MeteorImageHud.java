/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package io.github.stashya.donutaddon.hud;

import io.github.stashya.donutaddon.DonutAddon;
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
            imageHud.configureAsWatermark("/assets/donutAddon/textures/logo1.png", 120, 0.44);
        });
    }

    private static ImageHud create() {
        return new ImageHud(INFO);
    }
}
