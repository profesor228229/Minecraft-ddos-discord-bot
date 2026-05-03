package me.devin.parkourbot.mixin;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.freecam.Freecam;
import me.devin.parkourbot.client.movement.MovementExecutor;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Override the player's input fields after the vanilla {@link KeyboardInput#tick(boolean)}
 * has read the keybindings.
 *
 * <p>Two cases:
 * <ul>
 *   <li>{@link Freecam} is enabled -> zero everything so the player stands still while we
 *   fly the camera around.</li>
 *   <li>{@link MovementExecutor#overrideInput} is set -> copy its desired forward / jump /
 *   sneak fields onto the player input.</li>
 * </ul>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void parkourbot_override(boolean slowDown, CallbackInfo ci) {
        if (Freecam.INSTANCE.isEnabled()) {
            this.movementForward = 0.0F;
            this.movementSideways = 0.0F;
            this.pressingForward = false;
            this.pressingBack = false;
            this.pressingLeft = false;
            this.pressingRight = false;
            this.jumping = false;
            this.sneaking = false;
            return;
        }

        MovementExecutor exec = MovementExecutor.INSTANCE;
        if (exec.overrideInput && ParkourBotClient.state().isEnabled()) {
            this.movementForward = exec.forward;
            this.movementSideways = exec.sideways;
            this.pressingForward = exec.pressingForward;
            this.pressingBack = exec.pressingBack;
            this.pressingLeft = exec.pressingLeft;
            this.pressingRight = exec.pressingRight;
            this.jumping = exec.jumping;
            this.sneaking = exec.sneaking;
        }
    }
}
