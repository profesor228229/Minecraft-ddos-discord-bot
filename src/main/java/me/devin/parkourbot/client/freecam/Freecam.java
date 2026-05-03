package me.devin.parkourbot.client.freecam;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.util.RayUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.LiteralText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Free camera controller. When enabled, the player stays still (their movement and look
 * deltas are intercepted) and we drive a virtual camera around using WASD / Space / LCtrl.
 *
 * <p>Mouse-look is captured via a mixin on {@code Entity#changeLookDirection} which calls
 * {@link #applyMouseDelta(double, double)} and cancels the player's look update.
 */
public final class Freecam {
    public static final Freecam INSTANCE = new Freecam();

    /** Base move speed in blocks per tick (~3 b/s). */
    private static final double BASE_SPEED = 0.35;
    /** Multiplier when sprint key (LCtrl) is held. */
    private static final double SPRINT_FACTOR = 3.0;

    private boolean enabled;
    private double posX, posY, posZ;
    private float yaw, pitch;

    private Freecam() {}

    public boolean isEnabled() { return enabled; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    public void toggle(MinecraftClient client) {
        if (client.player == null) return;
        if (enabled) {
            disable(client);
        } else {
            enable(client);
        }
    }

    public void enable(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) return;
        Vec3d eye = p.getCameraPosVec(1.0f);
        this.posX = eye.x;
        this.posY = eye.y;
        this.posZ = eye.z;
        this.yaw = p.yaw;
        this.pitch = p.pitch;
        this.enabled = true;
        p.sendMessage(new LiteralText("\u00a7b[ParkourBot] freecam ON (V to exit, RMB to set goal)"), false);
    }

    public void disable(MinecraftClient client) {
        this.enabled = false;
        if (client.player != null) {
            client.player.sendMessage(new LiteralText("\u00a7b[ParkourBot] freecam OFF"), false);
        }
    }

    /** Called from the {@code Entity#changeLookDirection} mixin. */
    public void applyMouseDelta(double cursorDeltaX, double cursorDeltaY) {
        // Same scaling as vanilla: 0.15 deg per pixel.
        float dYaw = (float) cursorDeltaX * 0.15f;
        float dPitch = (float) cursorDeltaY * 0.15f;
        this.yaw += dYaw;
        this.pitch += dPitch;
        this.pitch = MathHelper.clamp(this.pitch, -89.9f, 89.9f);
        // Keep yaw bounded so it doesn't drift forever in float-precision territory.
        if (this.yaw > 360.0f) this.yaw -= 360.0f;
        if (this.yaw < -360.0f) this.yaw += 360.0f;
    }

    /** Per-tick update: WASD / Space / LCtrl drive the camera, RMB drops a goal. */
    public void tick(MinecraftClient client) {
        if (!enabled) return;
        if (client.player == null || client.world == null) return;
        if (client.currentScreen != null) return; // pause / inventory

        GameOptions opts = client.options;
        double forward = (opts.keyForward.isPressed() ? 1 : 0) - (opts.keyBack.isPressed() ? 1 : 0);
        double strafe  = (opts.keyRight.isPressed()   ? 1 : 0) - (opts.keyLeft.isPressed() ? 1 : 0);
        double vertical = (opts.keyJump.isPressed()  ? 1 : 0) - (opts.keySneak.isPressed() ? 1 : 0);

        double speed = BASE_SPEED * (opts.keySprint.isPressed() ? SPRINT_FACTOR : 1.0);

        if (forward != 0 || strafe != 0 || vertical != 0) {
            double yawRad = Math.toRadians(this.yaw);
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);
            // Forward in MC is -Z when yaw=0.
            double dx = -sin * forward + cos * strafe;
            double dz =  cos * forward + sin * strafe;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1.0) { dx /= len; dz /= len; }
            this.posX += dx * speed;
            this.posZ += dz * speed;
            this.posY += vertical * speed;
        }

        // Right click -> set bot goal at the block we are pointing at.
        while (opts.keyUse.wasPressed()) {
            BlockHitResult hit = RayUtil.raycastFromFreecam(client, this);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                client.player.sendMessage(new LiteralText(
                        "\u00a7c[ParkourBot] freecam crosshair points at nothing"), false);
                continue;
            }
            BlockPos goal = hit.getBlockPos().up();
            ParkourBotClient.state().setGoal(goal);
            ParkourBotClient.state().setPath(null);
            client.player.sendMessage(new LiteralText(
                    "\u00a7e[ParkourBot] goal set: " + goal.getX() + " " + goal.getY() + " " + goal.getZ()), false);
        }
    }
}
