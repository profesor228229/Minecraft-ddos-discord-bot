package me.devin.parkourbot.mixin;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.freecam.Freecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-route the local player's mouse-look while freecam is on (the camera should rotate,
 * not the player) and while the bot is executing (we set rotation manually).
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void parkourbot_redirectLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || self != client.player) return;

        if (Freecam.INSTANCE.isEnabled()) {
            Freecam.INSTANCE.applyMouseDelta(cursorDeltaX, cursorDeltaY);
            ci.cancel();
            return;
        }
        if (ParkourBotClient.state().isExecuting()) {
            // Suppress mouse-look while the bot is driving the player. Otherwise small
            // mouse movements would re-aim the player away from its target.
            ci.cancel();
        }
    }
}
