package me.devin.parkourbot.client.movement;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.ParkourBotState;
import me.devin.parkourbot.client.pathfinder.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Drives the player along a {@link Path} produced by the pathfinder. The actual input
 * fields are written by the {@code KeyboardInputMixin}; this class only computes intent.
 *
 * <p>Per tick:
 * <ol>
 *   <li>Find the current target step.</li>
 *   <li>Aim the player's yaw at the target.</li>
 *   <li>Set forward/jump/sprint flags.</li>
 *   <li>Advance the step counter when close enough.</li>
 * </ol>
 */
public final class MovementExecutor {
    public static final MovementExecutor INSTANCE = new MovementExecutor();

    private int stepIndex;
    private int stuckTicks;
    private double lastX, lastZ;
    private boolean lastValid;

    // --- Read by KeyboardInputMixin ----------------------------------------------------
    public volatile boolean overrideInput;
    public volatile float forward;       // [-1..1]
    public volatile float sideways;      // [-1..1]
    public volatile boolean pressingForward;
    public volatile boolean pressingBack;
    public volatile boolean pressingLeft;
    public volatile boolean pressingRight;
    public volatile boolean jumping;
    public volatile boolean sneaking;
    // -----------------------------------------------------------------------------------

    private MovementExecutor() {}

    public void reset() {
        stepIndex = 0;
        stuckTicks = 0;
        lastValid = false;
        overrideInput = false;
        forward = 0; sideways = 0;
        pressingForward = pressingBack = pressingLeft = pressingRight = false;
        jumping = sneaking = false;
        ParkourBotClient.state().setExecuting(false);
    }

    public void tick(MinecraftClient client, ParkourBotState state) {
        Path path = state.getPath();
        ClientPlayerEntity p = client.player;
        if (path == null || p == null) { reset(); return; }

        // Skip steps already behind us.
        while (stepIndex < path.size() && reached(p, path.get(stepIndex).pos)) {
            stepIndex++;
        }
        if (stepIndex >= path.size()) {
            // Done.
            client.player.sendMessage(new LiteralText("\u00a7a[ParkourBot] arrived"), false);
            state.setEnabled(false);
            state.setPath(null);
            reset();
            return;
        }

        Path.Step step = path.get(stepIndex);
        BlockPos target = step.pos;

        // Aim at the centre of the target block.
        double tx = target.getX() + 0.5;
        double ty = target.getY();
        double tz = target.getZ() + 0.5;

        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.yaw = yaw;
        p.prevYaw = yaw;
        p.headYaw = yaw;
        p.bodyYaw = yaw;
        p.pitch = 0f;
        p.prevPitch = 0f;

        // Decide forward/jump/sprint based on the kind of movement.
        overrideInput = true;
        sideways = 0; pressingLeft = false; pressingRight = false;
        pressingBack = false; sneaking = false;

        forward = 1.0f;
        pressingForward = true;
        jumping = false;

        boolean wantSprint = false;
        switch (step.kind) {
            case WALK:
            case WALK_DIAG:
                wantSprint = true;
                break;
            case JUMP_UP:
                wantSprint = true;
                if (p.isOnGround() && horizDistSq(p, tx, tz) < 1.4 * 1.4) {
                    jumping = true;
                }
                break;
            case DROP:
                wantSprint = false; // walk off edge slowly to avoid overshoot
                break;
            case PARKOUR_GAP:
                wantSprint = true;
                // Need to jump *before* falling off. Trigger when standing close to the edge.
                if (p.isOnGround() && horizDistSq(p, tx, tz) < 2.2 * 2.2) {
                    jumping = true;
                }
                break;
            case START:
                forward = 0; pressingForward = false;
                break;
        }

        // Drive sprint state directly. setSprinting() only flips an internal flag and a
        // packet is sent next tick.
        if (wantSprint) {
            if (!p.isSprinting()) p.setSprinting(true);
        } else {
            if (p.isSprinting()) p.setSprinting(false);
        }

        // Stuck detection: if we haven't moved horizontally for ~3 seconds, replan.
        if (lastValid) {
            double moved = (p.getX() - lastX) * (p.getX() - lastX) + (p.getZ() - lastZ) * (p.getZ() - lastZ);
            if (moved < 0.0025) stuckTicks++;
            else stuckTicks = 0;
        }
        lastX = p.getX();
        lastZ = p.getZ();
        lastValid = true;

        if (stuckTicks > 60) {
            client.player.sendMessage(new LiteralText("\u00a7e[ParkourBot] stuck, replanning..."), false);
            stuckTicks = 0;
            stepIndex = 0;
            state.setPath(null);
        }

        state.setExecuting(true);
    }

    private boolean reached(ClientPlayerEntity p, BlockPos pos) {
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double dx = p.getX() - cx;
        double dz = p.getZ() - cz;
        double dy = p.getY() - pos.getY();
        return (dx * dx + dz * dz) < 0.36 && Math.abs(dy) < 0.6;
    }

    private static double horizDistSq(ClientPlayerEntity p, double tx, double tz) {
        double dx = p.getX() - tx;
        double dz = p.getZ() - tz;
        return dx * dx + dz * dz;
    }

    @SuppressWarnings("unused")
    private static float wrapDegrees(float yaw) {
        return MathHelper.wrapDegrees(yaw);
    }
}
