package me.devin.parkourbot.mixin;

import me.devin.parkourbot.client.freecam.Freecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While freecam is on, drain the attack/use/pick-block keypress queues BEFORE the
 * vanilla {@code handleInputEvents} loops do, so the player doesn't try to interact
 * with whatever happens to be at their stationary crosshair. We also forward
 * right-click to {@link Freecam#handleRightClick} so it can be used to set the bot
 * goal at the freecam camera's crosshair.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void parkourbot_freecamConsumeKeys(CallbackInfo ci) {
        if (!Freecam.INSTANCE.isEnabled()) return;
        MinecraftClient client = (MinecraftClient) (Object) this;

        while (client.options.keyUse.wasPressed()) {
            Freecam.INSTANCE.handleRightClick(client);
        }
        // Drain attack and pick-block so the game doesn't fire them from the player's
        // (frozen) crosshair while we are flying around.
        drain(client.options.keyAttack);
        drain(client.options.keyPickItem);
    }

    private static void drain(KeyBinding kb) {
        while (kb.wasPressed()) { /* swallow */ }
    }
}
