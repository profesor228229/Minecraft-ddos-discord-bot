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
        START,         // first node, no movement
        WALK,          // forward 1 block, same Y
        WALK_DIAG,     // forward 1 block diag, same Y
        JUMP_UP_1,     // forward 1, +1 Y (block jump)
        DROP,          // forward 1, -1..-3 Y
        PARKOUR2,      // walk-jump 2 forward (gap 1), same Y
        PARKOUR3,      // sprint-jump 3 forward (gap 2), same Y
        PARKOUR4,      // sprint-jump 4 forward (gap 3), same Y
        PARKOUR5,      // max sprint-jump 5 forward (gap 4), same Y (vanilla limit)
        PARKOUR_UP2,   // walk-jump 2 forward, +1 Y
        PARKOUR_UP3    // sprint-jump 3 forward, +1 Y
    }

    public static boolean isParkourJump(Kind k) {
        return k == Kind.PARKOUR2 || k == Kind.PARKOUR3
                || k == Kind.PARKOUR4 || k == Kind.PARKOUR5
                || k == Kind.PARKOUR_UP2 || k == Kind.PARKOUR_UP3;
    }

    /** Horizontal span covered by the move (number of blocks forward). */
    public static int horizontalSpan(Kind k) {
        switch (k) {
            case WALK: case WALK_DIAG: case JUMP_UP_1: case DROP: return 1;
            case PARKOUR2: case PARKOUR_UP2: return 2;
            case PARKOUR3: case PARKOUR_UP3: return 3;
            case PARKOUR4: return 4;
            case PARKOUR5: return 5;
            case START: default: return 0;
        }
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
