package me.devin.parkourbot.mixin;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPos")
    void parkourbot_setPos(double x, double y, double z);

    @Invoker("setRotation")
    void parkourbot_setRotation(float yaw, float pitch);
}
