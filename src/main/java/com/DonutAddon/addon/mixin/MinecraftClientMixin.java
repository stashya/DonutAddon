package com.DonutAddon.addon.mixin;

import com.DonutAddon.addon.modules.BStar;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MinecraftClient.class, priority = 999)
public class MinecraftClientMixin {

    @Shadow
    public ClientPlayerEntity player;

    @Shadow
    public GameOptions options;

    @Shadow
    public Screen currentScreen;

    // Make Minecraft always think it's focused
    @ModifyReturnValue(
        method = "isWindowFocused",
        at = @At("RETURN")
    )
    private boolean alwaysFocused(boolean original) {
        BStar bStar = Modules.get().get(BStar.class);
        if (bStar != null && bStar.isAlwaysFocused()) {
            return true;
        }
        return original;
    }

}
