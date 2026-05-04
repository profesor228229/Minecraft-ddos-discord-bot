package me.devin.parkourbot.client.movement;

import me.devin.parkourbot.client.ParkourBotClient;
import me.devin.parkourbot.client.ParkourBotState;
import me.devin.parkourbot.client.pathfinder.Path;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Drives the player along a {@link Path}.
 *
 * <p>Per tick:
 * <ol>
 *   <li>Skip steps already reached (advance step index).</li>
 *   <li>Yaw towards target.</li>
 *   <li>Set forward / sprint / jump flags depending on step kind.</li>
 *   <li>Latch jump per step to prevent double-jumps.</li>
 * </ol>
 *
 * <p>Sprint-jump trigger windows are eager: we jump as soon as the bot crosses
 * a "fire" threshold along the motion direction (instead of waiting for max
 * sprint speed and tight edge timing). This is more forgiving on courses where
 * the start block has no run-up space behind it.
 */
public final class MovementExecutor {
    public static final MovementExecutor INSTANCE = new MovementExecutor();

    /** Set true to spam parkour-state to the chat. Useful for tuning. */
    public static volatile boolean DEBUG = false;

    private int stepIndex;
    private int stuckTicks;
    private double lastX, lastZ;
    private boolean lastValid;
    private Path lastPath;

    /** True for the duration of the current step once a jump impulse has been issued. */
    private boolean jumpLatched;
    private int jumpLatchedStepIndex = -1;

    // --- Read by KeyboardInputMixin ----------------------------------------------------
    public volatile boolean overrideInput;
    public volatile float forward;
    public volatile float sideways;
    public volatile boolean pressingForward;
    public volatile boolean pressingBack;
    public volatile boolean pressingLeft;
    public volatile boolean pressingRight;
    public volatile boolean jumping;
    public volatile boolean sneaking;

    /** Read by {@code LivingEntityJumpMixin}. Caps post-jump horizontal velocity for
     *  the current jump impulse. 0 = no cap (full vanilla physics). */
    public volatile double capJumpVelocity;
    // -----------------------------------------------------------------------------------

    private MovementExecutor() {}

    public void reset() {
        stepIndex = 0;
        stuckTicks = 0;
        lastValid = false;
        lastPath = null;
        jumpLatched = false;
        jumpLatchedStepIndex = -1;
        overrideInput = false;
        forward = 0; sideways = 0;
        pressingForward = pressingBack = pressingLeft = pressingRight = false;
        jumping = sneaking = false;
        capJumpVelocity = 0;
        ParkourBotClient.state().setExecuting(false);
    }

    public int currentStepIndex() { return stepIndex; }

    public void tick(MinecraftClient client, ParkourBotState state) {
        Path path = state.getPath();
        ClientPlayerEntity p = client.player;
        if (path == null || p == null) { reset(); return; }

        if (path != lastPath) {
            stepIndex = 0;
            stuckTicks = 0;
            lastValid = false;
            jumpLatched = false;
            jumpLatchedStepIndex = -1;
            lastPath = path;
        }

        // Advance past steps we've already reached.
        while (stepIndex < path.size() && reached(p, path.get(stepIndex))) {
            stepIndex++;
            // Step changed: clear latch so the next jump can fire.
            if (jumpLatchedStepIndex < stepIndex - 1) {
                jumpLatched = false;
                jumpLatchedStepIndex = -1;
            }
        }
        if (stepIndex >= path.size()) {
            client.player.sendMessage(new LiteralText("\u00a7a[ParkourBot] arrived"), false);
            state.setEnabled(false);
            state.setPath(null);
            reset();
            return;
        }

        // Clear latch once we're back on ground AND past the latched step.
        if (jumpLatched && p.isOnGround() && stepIndex > jumpLatchedStepIndex) {
            jumpLatched = false;
            jumpLatchedStepIndex = -1;
        }

        Path.Step step = path.get(stepIndex);
        Path.Step prevStep = stepIndex > 0 ? path.get(stepIndex - 1) : null;
        BlockPos target = step.pos;

        double tx = target.getX() + 0.5;
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

        overrideInput = true;
        sideways = 0; pressingLeft = false; pressingRight = false;
        pressingBack = false; sneaking = false;
        forward = 1.0f;
        pressingForward = true;
        jumping = false;
        capJumpVelocity = 0;
        boolean wantSprint = false;

        ParkourState pk = computeParkourState(p, step, prevStep);

        Path.Step nextStep = (stepIndex + 1 < path.size()) ? path.get(stepIndex + 1) : null;
        boolean nextIsShortJump = nextStep != null
                && (nextStep.kind == Path.Kind.PARKOUR2 || nextStep.kind == Path.Kind.PARKOUR_UP2);

        switch (step.kind) {
            case WALK:
            case WALK_DIAG:
                // Sprint by default — but slow down 1 block before a short parkour
                // (PARKOUR2 needs walk-speed entry to avoid overshooting the target).
                wantSprint = !nextIsShortJump || horizDistSq(p, tx, tz) > 0.6 * 0.6;
                break;
            case JUMP_UP_1:
                wantSprint = true;
                if (p.isOnGround() && horizDistSq(p, tx, tz) < 1.4 * 1.4) {
                    jumping = true;
                    capJumpVelocity = 0.20; // gentle hop
                }
                break;
            case DROP:
                wantSprint = false;
                break;
            case PARKOUR2:
            case PARKOUR_UP2:
                // Walk-jump. Mixin sees cap < 0.30 and disables the sprint impulse pre-jump.
                wantSprint = false;
                jumping = shouldJumpForParkour(p, step, prevStep, pk);
                if (jumping) capJumpVelocity = capForKind(step.kind);
                break;
            case PARKOUR3:
            case PARKOUR_UP3:
            case PARKOUR4:
                // Sprint-jump with cap so accumulated velocity doesn't compound
                // across consecutive jumps.
                wantSprint = true;
                jumping = shouldJumpForParkour(p, step, prevStep, pk);
                if (jumping) capJumpVelocity = capForKind(step.kind);
                break;
            case PARKOUR5:
                // Vanilla 1.16.5 max — 5-block jump (4-block gap). No cap; need every
                // drop of horizontal momentum to clear the gap.
                wantSprint = true;
                jumping = shouldJumpForParkour(p, step, prevStep, pk);
                break;
            case START:
                forward = 0; pressingForward = false;
                break;
        }

        // Suppress double-jumping for the same step.
        if (jumping) {
            if (jumpLatched && jumpLatchedStepIndex == stepIndex) {
                jumping = false;
            } else {
                jumpLatched = true;
                jumpLatchedStepIndex = stepIndex;
            }
        }

        if (wantSprint) {
            if (!p.isSprinting()) p.setSprinting(true);
        } else {
            if (p.isSprinting()) p.setSprinting(false);
        }

        // Stuck detection.
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

        if (DEBUG && (Path.isParkourJump(step.kind) || step.kind == Path.Kind.JUMP_UP_1)) {
            int targetSpan = Path.horizontalSpan(step.kind);
            client.player.sendMessage(new LiteralText(
                    String.format("\u00a77step=%d %s rel=%.2f speed=%.3f gnd=%s jmp=%s pred=%.2f@t%d tgt=%d",
                            stepIndex, step.kind, pk.rel, pk.speed,
                            p.isOnGround(), jumping,
                            lastSimDiag[0], (int) lastSimDiag[1], targetSpan)), true);
        }

        state.setExecuting(true);
    }

    private static final class ParkourState {
        final boolean valid;
        final double rel;
        final double relNext;
        final double speed;
        final double dirX, dirZ;
        ParkourState(boolean valid, double rel, double relNext, double speed, double dirX, double dirZ) {
            this.valid = valid; this.rel = rel; this.relNext = relNext; this.speed = speed;
            this.dirX = dirX; this.dirZ = dirZ;
        }
    }

    private ParkourState computeParkourState(ClientPlayerEntity p, Path.Step step, Path.Step prevStep) {
        if (prevStep == null) return new ParkourState(false, 0, 0, 0, 0, 0);
        BlockPos prev = prevStep.pos;
        double pcx = prev.getX() + 0.5;
        double pcz = prev.getZ() + 0.5;
        double tcx = step.pos.getX() + 0.5;
        double tcz = step.pos.getZ() + 0.5;
        double dirX = tcx - pcx;
        double dirZ = tcz - pcz;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1e-3) return new ParkourState(false, 0, 0, 0, 0, 0);
        dirX /= len; dirZ /= len;

        double rel = (p.getX() - pcx) * dirX + (p.getZ() - pcz) * dirZ;
        Vec3d v = p.getVelocity();
        double vRel = v.x * dirX + v.z * dirZ;
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        return new ParkourState(true, rel, rel + vRel, speed, dirX, dirZ);
    }

    /**
     * Per-kind velocity cap used both by the live mixin and the simulator.
     *
     * <p>Caps are tuned so that on the jump tick (which adds ground-acc 0.1725
     * to capped vel before move), the resulting flight lands near target center.
     * Lower caps trade range for landing accuracy; PARKOUR5 is uncapped because
     * 4-block gaps need every drop of momentum.
     */
    static double capForKind(Path.Kind k) {
        switch (k) {
            case PARKOUR2:      return 0.10; // lowered from 0.14: chained walk-jumps
                                              //   accumulated rel offset and overshot.
            case PARKOUR_UP2:   return 0.12;
            case PARKOUR3:      return 0.32;
            case PARKOUR_UP3:   return 0.34;
            case PARKOUR4:      return 0.42;
            case PARKOUR5:      return 0.0; // unbounded
            case JUMP_UP_1:     return 0.20;
            default:            return 0.0;
        }
    }

    /**
     * Decide whether to fire jump for a parkour step this tick.
     *
     * <p><b>Algorithm:</b> Multi-tick lookahead. Try jumping at t=0, 1, 2, ..., LOOKAHEAD
     * ticks ahead. For each t we simulate (a) ground-walking forward t ticks (slipperiness
     * 0.6, sprint acc 0.13 / walk 0.10) then (b) jumping with vanilla physics. We pick the
     * t whose predicted landing is closest to target rel. Fire iff t==0 wins — i.e. waiting
     * any longer would worsen accuracy.
     *
     * <p>This solves the classic standstill problem: from rel=0, vel=0 a jump-now lands
     * short of the gap, so the bot waits ground-walking until pre-jump velocity is high
     * enough that an immediate jump lands cleanly on target — then fires.
     */
    private static final int LOOKAHEAD_TICKS = 6;
    private double[] lastSimDiag = new double[]{-2, -2, 0}; // [predicted, bestT, bestErr] for HUD

    private boolean shouldJumpForParkour(ClientPlayerEntity p, Path.Step step,
                                         Path.Step prevStep, ParkourState pk) {
        if (!p.isOnGround()) return false;
        if (!pk.valid) return false;
        if (Math.abs(p.getY() - prevStep.pos.getY()) > 0.4) return false;
        if (pk.rel > 0.55) return false;

        boolean walkJump = (step.kind == Path.Kind.PARKOUR2 || step.kind == Path.Kind.PARKOUR_UP2);
        double cap = capForKind(step.kind);
        double targetRel = Path.horizontalSpan(step.kind);
        double landingY  = step.pos.getY() + 1.0;
        double pcx = prevStep.pos.getX() + 0.5;
        double pcz = prevStep.pos.getZ() + 0.5;
        double yawRad = Math.toRadians(p.yaw);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        // Vanilla 1.16.5 ground physics on a stone-like block (slipperiness 0.6):
        //   d6 = slipperiness * 0.91 = 0.546
        //   accel = baseSpeed * (0.21600002 / d6^3) ≈ baseSpeed * 1.327
        // Sprint baseSpeed = 0.13 → 0.1725; walk baseSpeed = 0.10 → 0.1327.
        double groundAcc = walkJump ? 0.1327 : 0.1725;
        double slipperiness = 0.546;

        Vec3d v = p.getVelocity();
        double sx = p.getX();
        double sy = p.getY();
        double sz = p.getZ();
        double svx = v.x;
        double svz = v.z;

        double bestErr = Double.MAX_VALUE;
        int bestT = -1;
        double bestPred = -2;

        for (int t = 0; t <= LOOKAHEAD_TICKS; t++) {
            // Stop if we'd already be past the takeoff edge.
            double curRel = (sx - pcx) * pk.dirX + (sz - pcz) * pk.dirZ;
            if (curRel > 0.55) break;

            double pred = simulateLandingRel(sx, sy, sz, svx, svz,
                    fwdX, fwdZ, walkJump, cap,
                    landingY, pcx, pcz, pk.dirX, pk.dirZ);

            if (pred >= 0) {
                boolean inWindow = pred >= targetRel - 0.42 && pred <= targetRel + 0.42;
                double err = Math.abs(pred - targetRel);
                if (inWindow && err < bestErr) {
                    bestErr = err;
                    bestT = t;
                    bestPred = pred;
                }
            }

            // Advance one ground-walk tick (vanilla physics):
            //   updateVelocity(speed, input)  → v += fwd * groundAcc
            //   move(SELF, v)                 → pos += v
            //   v.xz *= slipperiness          → friction
            svx += fwdX * groundAcc;
            svz += fwdZ * groundAcc;
            sx += svx;
            sz += svz;
            svx *= slipperiness;
            svz *= slipperiness;
        }

        // If lookahead found NO feasible tick, but rel > 0.40 we're about to fall
        // off the takeoff block — fire anyway as a last resort. Use jump-now prediction.
        if (bestT < 0 && pk.rel > 0.40) {
            double last = simulateLandingRel(p.getX(), p.getY(), p.getZ(), v.x, v.z,
                    fwdX, fwdZ, walkJump, cap,
                    landingY, pcx, pcz, pk.dirX, pk.dirZ);
            lastSimDiag[0] = last;
            lastSimDiag[1] = -1;
            lastSimDiag[2] = 0;
            return last >= targetRel - 0.6;
        }

        lastSimDiag[0] = bestPred;
        lastSimDiag[1] = bestT;
        lastSimDiag[2] = bestErr == Double.MAX_VALUE ? 0 : bestErr;

        return bestT == 0;
    }

    /**
     * Forward-simulate a jump from explicit start state (sx, sy, sz, svx, svz).
     * Returns predicted landing rel along (dirX, dirZ) from (pcx, pcz), or -1 if no landing.
     *
     * <p>Mirrors vanilla 1.16.5 {@code LivingEntity.travel} order:
     * <ol>
     *   <li>updateVelocity(speed, input) → v.xz += fwd * acc</li>
     *   <li>move(SELF, v) → pos += v</li>
     *   <li>gravity v.y -= 0.08</li>
     *   <li>drag v *= drag (slipperiness*0.91 on ground, 0.91 in air)</li>
     * </ol>
     *
     * <p>The first tick uses GROUND physics (slip*0.91=0.546, sprint acc 0.1725)
     * because {@code onGround} is true at the start of the jump tick — only after
     * {@code move()} pushes the player up does the engine update onGround=false.
     */
    private static final int SIM_MAX_TICKS = 40;
    private double simulateLandingRel(double sx, double sy, double sz, double svx, double svz,
                                      double fwdX, double fwdZ, boolean walkJump, double cap,
                                      double landingY, double pcx, double pcz,
                                      double dirX, double dirZ) {
        double vx = svx;
        double vy = 0.42; // jump impulse overrides any vy
        double vz = svz;
        if (!walkJump) {
            // sprint impulse +0.2 in yaw direction
            vx += fwdX * 0.2;
            vz += fwdZ * 0.2;
        }
        // Apply cap (mixin enforces this in real life via LivingEntityJumpMixin)
        if (cap > 0.0) {
            double sp = Math.sqrt(vx * vx + vz * vz);
            if (sp > cap) {
                double s = cap / sp;
                vx *= s; vz *= s;
            }
        }

        double px = sx;
        double py = sy;
        double pz = sz;

        // Tick 0 (jump tick) — onGround=true at start, ground physics applies horizontally.
        double groundDrag    = 0.546;            // slipperiness * 0.91
        double groundAccSprint = walkJump ? 0.1327 : 0.1725;
        double airDrag       = 0.91;
        double airAcc        = walkJump ? 0.02 : 0.026;

        for (int t = 0; t < SIM_MAX_TICKS; t++) {
            boolean ground = (t == 0); // first tick uses ground physics
            double drag = ground ? groundDrag : airDrag;
            double acc  = ground ? groundAccSprint : airAcc;

            // 1) updateVelocity: v.xz += fwd * acc.
            vx += fwdX * acc;
            vz += fwdZ * acc;

            // Landing detection BEFORE move: if vy<0 and py crosses landingY this tick, interp.
            if (vy < 0 && t > 0 && py >= landingY && py + vy <= landingY) {
                double alpha = (py - landingY) / (-vy);
                if (alpha < 0) alpha = 0;
                if (alpha > 1) alpha = 1;
                double landX = px + vx * alpha;
                double landZ = pz + vz * alpha;
                return (landX - pcx) * dirX + (landZ - pcz) * dirZ;
            }

            // 2) move: pos += v.
            px += vx; py += vy; pz += vz;

            // 3) gravity: vy -= 0.08.
            vy -= 0.08;

            // 4) drag: v.xz *= drag, vy *= 0.98.
            vx *= drag;
            vz *= drag;
            vy *= 0.98;
        }
        return -1;
    }

    /** A step is "reached" when the player is inside the target block's xz footprint. */
    private boolean reached(ClientPlayerEntity p, Path.Step step) {
        BlockPos pos = step.pos;
        double cx = pos.getX() + 0.5;
        double cz = pos.getZ() + 0.5;
        double dx = p.getX() - cx;
        double dz = p.getZ() - cz;
        double dy = p.getY() - pos.getY();
        // Inside the block's xz footprint at roughly the right Y, on the ground.
        return Math.abs(dx) < 0.50 && Math.abs(dz) < 0.50
                && dy < 0.6 && dy > -0.6
                && p.isOnGround();
    }

    private static double horizDistSq(ClientPlayerEntity p, double tx, double tz) {
        double dx = p.getX() - tx;
        double dz = p.getZ() - tz;
        return dx * dx + dz * dz;
    }

}
