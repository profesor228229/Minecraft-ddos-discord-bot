package me.devin.parkourbot.mixin;

import me.devin.parkourbot.client.movement.MovementExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps horizontal velocity right after the vanilla jump impulse fires, so the
 * bot's parkour jumps land where the executor expects regardless of the player's
 * velocity history (residual sprint speed, auto-resprint, etc.).
 *
 * <p>The cap is configured by {@link MovementExecutor#capJumpVelocity}; if 0 we
 * leave the velocity untouched (full vanilla physics — used for max-distance
 * jumps like PARKOUR4).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityJumpMixin {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("ParkourBot");

    /** Cap value below which we treat the jump as a walk-jump (no sprint impulse). */
    private static final double WALK_JUMP_CAP_THRESHOLD = 0.30;

    /** Pre-jump: if this is a walk-jump, force sprint OFF so the vanilla sprint
     *  impulse (+0.2 horizontal) doesn't fire and overshoot. */
    @Inject(method = "jump", at = @At("HEAD"))
    private void parkourbot_preJump(CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity p = (ClientPlayerEntity) self;
        if (p != MinecraftClient.getInstance().player) return;

        MovementExecutor exec = MovementExecutor.INSTANCE;
        if (!exec.overrideInput) return;
        double cap = exec.capJumpVelocity;
        if (cap > 0.0 && cap < WALK_JUMP_CAP_THRESHOLD) {
            if (p.isSprinting()) {
                p.setSprinting(false); // suppresses the +0.2 impulse inside jump()
            }
            // Also cap pre-jump horizontal velocity so a residual sprint speed doesn't
            // carry through.
            Vec3d v = p.getVelocity();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            if (speed > cap) {
                double scale = cap / speed;
                p.setVelocity(v.x * scale, v.y, v.z * scale);
            }
        }
    }

    /** Post-jump: cap the final horizontal speed (after the sprint impulse, if any). */
    @Inject(method = "jump", at = @At("TAIL"))
    private void parkourbot_postJump(CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof ClientPlayerEntity)) return;
        ClientPlayerEntity p = (ClientPlayerEntity) self;
        if (p != MinecraftClient.getInstance().player) return;

        MovementExecutor exec = MovementExecutor.INSTANCE;
        if (!exec.overrideInput) return;
        double cap = exec.capJumpVelocity;

        Vec3d v = p.getVelocity();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);

        if (cap > 0.0 && speed > cap) {
            double scale = cap / speed;
            p.setVelocity(v.x * scale, v.y, v.z * scale);
            if (MovementExecutor.DEBUG) {
                LOGGER.info("[ParkourBot] jump capped: speed={} -> {}, sprinting={}",
                        String.format("%.3f", speed),
                        String.format("%.3f", cap),
                        p.isSprinting());
            }
        } else if (MovementExecutor.DEBUG) {
            LOGGER.info("[ParkourBot] jump uncapped: speed={}, cap={}, sprinting={}",
                    String.format("%.3f", speed),
                    String.format("%.3f", cap),
                    p.isSprinting());
        }
        // Reset cap so it doesn't accidentally apply to the next manual jump.
        exec.capJumpVelocity = 0;
    }
}
