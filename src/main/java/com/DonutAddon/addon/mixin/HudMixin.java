package com.DonutAddon.addon.mixin;

import com.DonutAddon.addon.hud.ImageHud;
import com.DonutAddon.addon.hud.MeteorImageHud;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.XAnchor;
import meteordevelopment.meteorclient.systems.hud.YAnchor;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;

@Mixin(value = Hud.class, remap = false)
public abstract class HudMixin implements Iterable<HudElement> {

    @Shadow
    public abstract void add(HudElementInfo<?>.Preset preset, int x, int y, XAnchor xAnchor, YAnchor yAnchor);

    // Inject after HUD initializes
    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        ensureWatermarkExists();
    }

    // Inject after HUD loads from tag (saved data)
    @Inject(method = "fromTag", at = @At("TAIL"))
    private void onFromTag(NbtCompound tag, CallbackInfoReturnable<Hud> cir) {
        ensureWatermarkExists();
    }

    // Also inject when resetting to defaults
    @Inject(method = "resetToDefaultElementsImpl", at = @At("TAIL"))
    private void onResetToDefaults(CallbackInfo ci) {
        ensureWatermarkExists();
    }

    private void ensureWatermarkExists() {
        // Check if watermark already exists
        boolean found = false;
        ImageHud existingWatermark = null;

        for (HudElement element : this) {
            if (element.info != null && element.info.name.equals("donut-image")) {
                found = true;
                if (element instanceof ImageHud) {
                    existingWatermark = (ImageHud) element;
                }
                break;
            }
        }

        if (found && existingWatermark != null) {
            // Element exists, ensure it's configured as watermark
            existingWatermark.configureAsWatermark("/assets/donutaddon/textures/logo1.png", 120, 0.5);
        } else if (!found) {
            // Add using the PRESET which includes the configuration
            this.add(MeteorImageHud.WATERMARK, -4, 4, XAnchor.Right, YAnchor.Top);
        }
    }
}
