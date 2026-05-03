package me.devin.parkourbot.mixin;

import me.devin.parkourbot.client.freecam.Freecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * If freecam is on, override the camera's position and rotation after vanilla has set
 * them based on the player's eye.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void parkourbot_freecam(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        Freecam fc = Freecam.INSTANCE;
        if (!fc.isEnabled()) return;
        CameraAccessor self = (CameraAccessor) this;
        self.parkourbot_setRotation(fc.getYaw(), fc.getPitch());
        self.parkourbot_setPos(fc.getPosX(), fc.getPosY(), fc.getPosZ());
    }
}
