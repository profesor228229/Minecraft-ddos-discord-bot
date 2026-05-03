package me.devin.parkourbot.client.pathfinder;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;

/**
 * Result of a successful path search. Each {@link Step} is the {@link BlockPos} at which
 * the player's feet should arrive, plus how to get there from the previous step.
 */
public final class Path {
    private final List<Step> steps;

    public Path(List<Step> steps) {
        this.steps = Objects.requireNonNull(steps);
    }

    public List<Step> getSteps() { return steps; }
    public int size() { return steps.size(); }
    public Step get(int i) { return steps.get(i); }

    public enum Kind {
        START,        // first node, no movement
        WALK,         // forward 1 block, same Y
        WALK_DIAG,    // forward 1 block diag, same Y
        JUMP_UP,      // forward 1, +1 Y (block jump)
        DROP,         // forward 1, -d Y (drop)
        PARKOUR_GAP   // forward 2 across a gap, same Y
    }

    public static final class Step {
        public final BlockPos pos;
        public final Kind kind;
        public final BlockPos prev;

        public Step(BlockPos pos, Kind kind, BlockPos prev) {
            this.pos = pos;
            this.kind = kind;
            this.prev = prev;
        }
    }
}
