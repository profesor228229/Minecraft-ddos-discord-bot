package me.devin.parkourbot.client.util;

import me.devin.parkourbot.client.freecam.Freecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class RayUtil {
    private RayUtil() {}

    /** Player-eye raycast, used by the "set goal at crosshair" key when freecam is off. */
    public static BlockHitResult raycastFromEntity(Entity entity, double maxDistance) {
        Vec3d start = entity.getCameraPosVec(1.0f);
        Vec3d look = entity.getRotationVec(1.0f);
        Vec3d end = start.add(look.x * maxDistance, look.y * maxDistance, look.z * maxDistance);
        return entity.world.raycast(new RaycastContext(
                start, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                entity));
    }

    /** Raycast from the freecam's camera position/orientation. */
    public static BlockHitResult raycastFromFreecam(MinecraftClient client, Freecam cam) {
        if (client.player == null) return null;
        Vec3d start = new Vec3d(cam.getPosX(), cam.getPosY(), cam.getPosZ());

        float yaw = cam.getYaw();
        float pitch = cam.getPitch();
        float yawR = -yaw * ((float) Math.PI / 180.0F) - (float) Math.PI;
        float pitchR = -pitch * ((float) Math.PI / 180.0F);
        float cosYaw = MathHelper.cos(yawR);
        float sinYaw = MathHelper.sin(yawR);
        float cosPitch = -MathHelper.cos(pitchR);
        float sinPitch = MathHelper.sin(pitchR);
        Vec3d look = new Vec3d(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);

        double maxDistance = 256.0;
        Vec3d end = start.add(look.x * maxDistance, look.y * maxDistance, look.z * maxDistance);
        return client.world.raycast(new RaycastContext(
                start, end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                client.player));
    }
}
