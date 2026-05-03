package me.devin.parkourbot.client.pathfinder;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* pathfinder that produces a {@link Path} of block positions the player should stand on.
 *
 * <p>Supported movements:
 * <ul>
 *   <li>4 cardinal walks (cost 1)</li>
 *   <li>4 diagonal walks (cost 1.41) requiring both side cells be passable</li>
 *   <li>Jump-up by 1 block (cost 1.5)</li>
 *   <li>Drop down by 1..3 blocks (cost 1.1 / 1.3 / 1.6)</li>
 *   <li>Parkour across a 1-block gap (cost 2.0)</li>
 * </ul>
 *
 * <p>Limits expansion to {@link #MAX_EXPANSIONS} nodes and a search radius of
 * {@link #MAX_DISTANCE} blocks from start.
 */
public final class Pathfinder {

    private static final int MAX_EXPANSIONS = 20_000;
    private static final int MAX_DISTANCE = 256;

    private final ClientWorld world;

    public Pathfinder(ClientWorld world) {
        this.world = world;
    }

    public static final class Result {
        public final Path path;
        public final String reason;
        public Result(Path path, String reason) { this.path = path; this.reason = reason; }
    }

    public Result findPath(BlockPos start, BlockPos goal) {
        // Snap start to a standable position (drop the player onto the ground if mid-air).
        BlockPos s = snapToFloor(start, 4);
        BlockPos g = snapToFloor(goal, 4);
        if (s == null) return new Result(null, "start not standable");
        if (g == null) return new Result(null, "goal not standable");

        if (s.equals(g)) {
            List<Path.Step> only = new ArrayList<>();
            only.add(new Path.Step(s, Path.Kind.START, null));
            return new Result(new Path(only), "already at goal");
        }

        HashMap<BlockPos, Node> all = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        Node startNode = new Node(s, null, Path.Kind.START, 0.0, heuristic(s, g));
        all.put(s, startNode);
        open.add(startNode);

        int expansions = 0;
        Node best = startNode;
        double bestH = startNode.h;

        while (!open.isEmpty()) {
            if (expansions++ > MAX_EXPANSIONS) {
                return new Result(reconstruct(best), "too many expansions, returning best-effort");
            }
            Node cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;

            if (cur.pos.equals(g)) {
                return new Result(reconstruct(cur), "ok");
            }
            if (cur.h < bestH) { bestH = cur.h; best = cur; }

            // Cap radius.
            if (cur.pos.getManhattanDistance(s) > MAX_DISTANCE) continue;

            for (Edge e : neighbours(cur.pos)) {
                Node next = all.get(e.pos);
                double newG = cur.g + e.cost;
                if (next == null) {
                    next = new Node(e.pos, cur, e.kind, newG, heuristic(e.pos, g));
                    all.put(e.pos, next);
                    open.add(next);
                } else if (!next.closed && newG < next.g) {
                    next.g = newG;
                    next.parent = cur;
                    next.kind = e.kind;
                    open.add(next); // re-add (lazy decrease-key)
                }
            }
        }
        return new Result(reconstruct(best), "exhausted, returning best-effort");
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Path reconstruct(Node end) {
        ArrayList<Path.Step> steps = new ArrayList<>();
        Node cur = end;
        while (cur != null) {
            steps.add(new Path.Step(cur.pos, cur.kind, cur.parent != null ? cur.parent.pos : null));
            cur = cur.parent;
        }
        Collections.reverse(steps);
        return new Path(steps);
    }

    // --- Movement enumeration ----------------------------------------------------------

    private static final Direction[] CARDINALS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
    private static final int[][] DIAGONALS = {
            { 1,  1}, { 1, -1}, {-1,  1}, {-1, -1}
    };

    private List<Edge> neighbours(BlockPos pos) {
        ArrayList<Edge> out = new ArrayList<>(16);

        // 1) Walk cardinals (also covers walk-up-jump and walk-down-drop).
        for (Direction d : CARDINALS) {
            BlockPos forward = pos.offset(d);

            // Same level walk?
            if (canStand(forward) && passable(pos.up()) /* head clear at start */) {
                out.add(new Edge(forward, Path.Kind.WALK, 1.0));
                continue;
            }
            // Jump up?
            BlockPos up = forward.up();
            if (canStand(up) && passable(pos.up().up()) && passable(pos.up())) {
                out.add(new Edge(up, Path.Kind.JUMP_UP, 1.5));
            }
            // Drop down 1..3?
            for (int drop = 1; drop <= 3; drop++) {
                BlockPos d2 = forward.down(drop);
                // All blocks between (exclusive of d2, inclusive of forward) must be passable.
                boolean clear = true;
                for (int k = 0; k < drop; k++) {
                    if (!passable(forward.down(k))) { clear = false; break; }
                }
                if (clear && canStand(d2) && passable(pos.up())) {
                    double cost = 1.0 + 0.1 * drop;
                    out.add(new Edge(d2, Path.Kind.DROP, cost));
                    break; // only take the first valid drop
                }
            }
            // Parkour gap 1?
            BlockPos far = pos.offset(d, 2);
            // gap = forward block has no floor (forward.down() not solid floor) and forward is passable+head clear.
            if (!canStand(forward)
                    && passable(forward) && passable(forward.up())
                    && canStand(far)
                    && passable(pos.up()) && passable(pos.up().up())) {
                out.add(new Edge(far, Path.Kind.PARKOUR_GAP, 2.0));
            }
        }

        // 2) Diagonal walks.
        for (int[] d : DIAGONALS) {
            BlockPos diag = pos.add(d[0], 0, d[1]);
            BlockPos sideX = pos.add(d[0], 0, 0);
            BlockPos sideZ = pos.add(0, 0, d[1]);
            if (canStand(diag)
                    && passable(sideX) && passable(sideX.up())
                    && passable(sideZ) && passable(sideZ.up())
                    && passable(pos.up())) {
                out.add(new Edge(diag, Path.Kind.WALK_DIAG, 1.41));
            }
        }

        return out;
    }

    // --- Block checks ------------------------------------------------------------------

    /**
     * The player can stand at {@code pos} (their feet at this block) if:
     * - block at pos is passable,
     * - block at pos.up() is passable (head),
     * - block at pos.down() is a walkable floor.
     */
    public boolean canStand(BlockPos pos) {
        return passable(pos) && passable(pos.up()) && walkableFloor(pos.down());
    }

    /** Block has no solid collision shape -> player can occupy this block. */
    public boolean passable(BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        // Lava / fire / cactus etc are technically passable but we don't want to walk through them.
        if (st.getMaterial().isBurnable() && st.getMaterial().blocksMovement()) return false;
        if (st.getMaterial() == net.minecraft.block.Material.LAVA) return false;
        if (st.getMaterial() == net.minecraft.block.Material.FIRE) return false;
        if (st.getBlock() == net.minecraft.block.Blocks.CACTUS) return false;
        return st.getCollisionShape(world, pos).isEmpty();
    }

    /** Block at {@code pos} has a full top face that the player can stand on. */
    public boolean walkableFloor(BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        // Reject any liquid (we don't want to swim).
        if (!st.getFluidState().isEmpty()) return false;
        // Reject blocks where falling onto them is bad.
        if (st.getBlock() == net.minecraft.block.Blocks.MAGMA_BLOCK) return false;
        if (st.getBlock() == net.minecraft.block.Blocks.CACTUS) return false;
        // Has a top-face we can stand on?
        return !st.getCollisionShape(world, pos).isEmpty()
                && st.getCollisionShape(world, pos).getMax(Direction.Axis.Y) >= 1.0 - 1.0e-3;
    }

    /**
     * If the block below {@code pos} is air, drop the position down up to {@code maxDrop}
     * blocks until we find a standable spot.
     */
    public BlockPos snapToFloor(BlockPos pos, int maxDrop) {
        if (canStand(pos)) return pos;
        for (int i = 1; i <= maxDrop; i++) {
            BlockPos p = pos.down(i);
            if (canStand(p)) return p;
        }
        // Try one block up (in case the goal is the floor block itself).
        BlockPos up = pos.up();
        if (canStand(up)) return up;
        return null;
    }

    private static final class Edge {
        final BlockPos pos;
        final Path.Kind kind;
        final double cost;
        Edge(BlockPos pos, Path.Kind kind, double cost) {
            this.pos = pos; this.kind = kind; this.cost = cost;
        }
    }

    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        Node parent;
        Path.Kind kind;
        double g, h;
        boolean closed;

        Node(BlockPos pos, Node parent, Path.Kind kind, double g, double h) {
            this.pos = pos; this.parent = parent; this.kind = kind; this.g = g; this.h = h;
        }

        @Override public int compareTo(Node o) {
            return Double.compare(this.g + this.h, o.g + o.h);
        }
    }
}
