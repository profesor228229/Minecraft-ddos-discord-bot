package me.devin.parkourbot.client.pathfinder;

import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.AbstractButtonBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.Material;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.TorchBlock;
import net.minecraft.block.TripwireBlock;
import net.minecraft.block.TripwireHookBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
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
 * <p>Movements:
 * <ul>
 *   <li>4 cardinal walks (cost 1.0)</li>
 *   <li>4 diagonal walks (cost 1.41) — both side cells must be clear</li>
 *   <li>Jump up 1 block (cost 1.5)</li>
 *   <li>Drop down 1..3 blocks (cost 1.1 / 1.3 / 1.7)</li>
 *   <li>Sprint-jump flat over 1 / 2 / 3 block gaps (cost 2.1 / 3.1 / 4.5)</li>
 *   <li>Sprint-jump up 1 with 1 / 2 block gaps (cost 2.7 / 4.0)</li>
 * </ul>
 *
 * <p>Each parkour edge requires the start block to have 2 blocks of head clearance, the
 * landing block to be standable, and every block of the trajectory to be passable at the
 * relevant heights.
 */
public final class Pathfinder {

    private static final int MAX_EXPANSIONS = 600_000;
    private static final int MAX_DISTANCE = 256;
    /** Don't explore positions whose Y differs from start/goal by more than this — keeps
     *  the bot from spilling into a giant grid below the parkour course. */
    private static final int Y_RADIUS = 6;
    /** Print pathfinder diagnostics on failure. */
    public static volatile boolean DEBUG = true;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("ParkourBot");

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
        BlockPos s = snapToFloor(start, 4);
        BlockPos g = snapToFloor(goal, 4);
        if (s == null) return new Result(null, "start not standable");
        if (g == null) return new Result(null, "goal not standable");

        final int yLo = Math.min(s.getY(), g.getY()) - Y_RADIUS;
        final int yHi = Math.max(s.getY(), g.getY()) + Y_RADIUS;

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
        int edgesGenerated = 0;
        Node best = startNode;
        double bestH = startNode.h;

        while (!open.isEmpty()) {
            if (expansions++ > MAX_EXPANSIONS) {
                if (DEBUG) {
                    LOGGER.info("[ParkourBot] pathfind EXHAUSTED after {} expansions, {} edges, best={} h={} (start={} goal={})",
                            expansions, edgesGenerated, best.pos, String.format("%.2f", bestH), s, g);
                }
                return new Result(reconstruct(best), "too many expansions, returning best-effort");
            }
            Node cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;

            if (cur.pos.equals(g)) {
                if (DEBUG) {
                    LOGGER.info("[ParkourBot] pathfind OK in {} expansions, {} edges (start={} goal={})",
                            expansions, edgesGenerated, s, g);
                }
                return new Result(reconstruct(cur), "ok");
            }
            if (cur.h < bestH) { bestH = cur.h; best = cur; }
            if (cur.pos.getManhattanDistance(s) > MAX_DISTANCE) continue;

            List<Edge> edges = neighbours(cur.pos);
            if (DEBUG && expansions <= 3) {
                LOGGER.info("[ParkourBot] expand #{} pos={} h={} edges={}",
                        expansions, cur.pos, String.format("%.2f", cur.h), edges.size());
                for (Edge ee : edges) {
                    LOGGER.info("  -> {} ({}) cost={}", ee.pos, ee.kind, String.format("%.2f", ee.cost));
                }
            }
            for (Edge e : edges) {
                // Y-bound prune: skip positions far above/below the start↔goal Y range.
                if (e.pos.getY() < yLo || e.pos.getY() > yHi) continue;
                edgesGenerated++;
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
                    open.add(next);
                }
            }
        }
        if (DEBUG) {
            LOGGER.info("[ParkourBot] pathfind EXHAUSTED queue after {} expansions, {} edges, best={} h={} (start={} goal={})",
                    expansions, edgesGenerated, best.pos, String.format("%.2f", bestH), s, g);
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
        ArrayList<Edge> out = new ArrayList<>(32);

        boolean headClear2 = passable(pos.up()) && passable(pos.up().up());

        for (Direction d : CARDINALS) {
            BlockPos f1 = pos.offset(d);

            // 1) Walk same level.
            if (canStand(f1)) {
                out.add(new Edge(f1, Path.Kind.WALK, 1.0));
            }
            // 2) Jump up 1.
            if (headClear2 && canStand(f1.up())
                    && passable(f1.up().up())) {
                out.add(new Edge(f1.up(), Path.Kind.JUMP_UP_1, 1.5));
            }
            // 3) Drop down 1..3.
            for (int drop = 1; drop <= 3; drop++) {
                BlockPos d2 = f1.down(drop);
                boolean clear = true;
                for (int k = 0; k < drop; k++) {
                    if (!passable(f1.down(k))) { clear = false; break; }
                }
                if (clear && canStand(d2) && passable(pos.up())) {
                    double cost = 1.0 + 0.2 * drop;
                    out.add(new Edge(d2, Path.Kind.DROP, cost));
                    break; // shortest valid drop
                }
            }
            // 4) Flat parkour: sprint jump over gaps. We try span 2..5 (gap 1..4).
            //    Vanilla 1.16.5 reach (verified by physics simulator):
            //      span 2 (gap 1): walk-jump, ~2.0 reach. From standstill OK.
            //      span 3 (gap 2): sprint-jump from standstill, ~2.93 reach. OK.
            //      span 4 (gap 3): sprint-jump, peak reach ~4.06 from a 1-block takeoff
            //                      (jumps at tick 2 with rel=0.44, vel=0.15). Edge case but OK.
            //      span 5 (gap 4): peak reach from 1-block standstill ~4.06 < 4.58 required.
            //                      MUST have runup behind takeoff to clear this gap.
            if (headClear2) {
                boolean hasRunup = hasRunupBehind(pos, d);
                for (int span = 2; span <= 5; span++) {
                    BlockPos target = pos.offset(d, span);
                    if (!flatJumpClear(pos, d, span)) break; // one blocked cell -> further is also blocked
                    if (!canStand(target)) continue;
                    Path.Kind k;
                    double cost;
                    switch (span) {
                        case 2: k = Path.Kind.PARKOUR2; cost = 2.1; break;
                        case 3: k = Path.Kind.PARKOUR3; cost = 3.1; break;
                        case 4: k = Path.Kind.PARKOUR4; cost = 4.5; break;
                        case 5: default: k = Path.Kind.PARKOUR5; cost = 6.5; break;
                    }
                    // span=5 (gap 4) is unreachable from a 1-block standstill — skip if no runup.
                    if (span == 5 && !hasRunup) continue;
                    // span=4 (gap 3) is reachable from standstill but tight; prefer runup paths.
                    if (span == 4 && !hasRunup) cost += 1.5;
                    out.add(new Edge(target, k, cost));
                }
            }
            // 5) Up parkour: sprint jump up 1 over a gap.
            if (headClear2) {
                for (int span = 2; span <= 3; span++) {
                    BlockPos target = pos.offset(d, span).up();
                    if (!upJumpClear(pos, d, span)) break;
                    if (!canStand(target)) continue;
                    Path.Kind k = (span == 2) ? Path.Kind.PARKOUR_UP2 : Path.Kind.PARKOUR_UP3;
                    double cost = (span == 2) ? 2.7 : 4.0;
                    out.add(new Edge(target, k, cost));
                }
            }
        }

        // 6) Diagonal walks.
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

    /** Trajectory check for a flat sprint jump of length {@code span} (in blocks) in direction {@code d}. */
    private boolean flatJumpClear(BlockPos start, Direction d, int span) {
        // Intermediate blocks (j=1..span-1) must be passable at Y and Y+1.
        for (int j = 1; j < span; j++) {
            BlockPos p = start.offset(d, j);
            if (!passable(p) || !passable(p.up())) return false;
        }
        return true;
    }

    /** True if there is a stand-on-able block within 2 blocks behind {@code pos}
     *  in the opposite of motion direction {@code d}. Used to gate sprint-jumps
     *  of 4-5 blocks that need acceleration space. */
    private boolean hasRunupBehind(BlockPos pos, Direction d) {
        Direction back = d.getOpposite();
        BlockPos b1 = pos.offset(back);
        if (canStand(b1)) return true;
        BlockPos b2 = pos.offset(back, 2);
        return canStand(b2);
    }

    /** Trajectory check for an upward sprint jump of length {@code span} that lands at Y+1. */
    private boolean upJumpClear(BlockPos start, Direction d, int span) {
        // Need the jump arc clear: at each intermediate block, Y, Y+1 and Y+2 passable
        // because the player rises through ~1.25 blocks above start.
        if (!passable(start.up().up())) return false;
        for (int j = 1; j < span; j++) {
            BlockPos p = start.offset(d, j);
            if (!passable(p) || !passable(p.up()) || !passable(p.up().up())) return false;
        }
        // Approach to landing: target.up() (head clearance over landing block).
        BlockPos lndAbove = start.offset(d, span).up().up();
        return passable(lndAbove);
    }

    // --- Block checks ------------------------------------------------------------------

    public boolean canStand(BlockPos pos) {
        return passable(pos) && passable(pos.up()) && walkableFloor(pos.down());
    }

    public boolean passable(BlockPos pos) {
        // If the chunk isn't loaded, treat as solid so we don't try to path through air.
        if (!world.isChunkLoaded(pos)) return false;
        BlockState st = world.getBlockState(pos);
        return passableState(st, pos);
    }

    /** Whether a player can move through a block-state without being stopped by it. */
    private boolean passableState(BlockState st, BlockPos pos) {
        // Hazardous: never path through.
        Material m = st.getMaterial();
        if (m == Material.LAVA || m == Material.FIRE) return false;
        if (st.getBlock() == Blocks.CACTUS) return false;
        if (st.getBlock() == Blocks.SWEET_BERRY_BUSH) return false;
        if (st.getBlock() == Blocks.WITHER_ROSE) return false;
        if (st.getBlock() == Blocks.MAGMA_BLOCK) return false;
        if (st.getBlock() == Blocks.HONEY_BLOCK) return false; // slows movement
        if (st.getBlock() == Blocks.COBWEB) return false;       // can't sprint through
        if (st.getBlock() == Blocks.SOUL_SAND) return false;    // slows movement
        if (st.getBlock() == Blocks.SCAFFOLDING) return false;

        // Decorative thin blocks — walk through freely.
        if (st.getBlock() == Blocks.END_ROD) return true;
        if (st.getBlock() instanceof TorchBlock) return true;
        if (st.getBlock() instanceof AbstractButtonBlock) return true;
        if (st.getBlock() instanceof LeverBlock) return true;
        if (st.getBlock() instanceof AbstractSignBlock) return true;
        if (st.getBlock() instanceof AbstractBannerBlock) return true;
        if (st.getBlock() instanceof TripwireBlock) return true;
        if (st.getBlock() instanceof TripwireHookBlock) return true;
        if (st.getBlock() instanceof RedstoneWireBlock) return true;
        if (st.getBlock() instanceof CarpetBlock) return true;
        if (st.getBlock() instanceof AbstractPressurePlateBlock) return true;

        // Open fence gates are passable; closed ones are not.
        if (st.getBlock() instanceof FenceGateBlock) {
            return st.contains(Properties.OPEN) && st.get(Properties.OPEN);
        }

        // Doors: same logic — open is passable.
        if (st.contains(Properties.OPEN) && st.getBlock().getClass().getSimpleName().contains("Door")) {
            return st.get(Properties.OPEN);
        }

        // Ladders/vines we treat as solid for now (we don't do climbing yet).
        if (st.getBlock() instanceof LadderBlock) return false;

        // Default: passable iff collision shape is empty.
        return st.getCollisionShape(world, pos).isEmpty();
    }

    public boolean walkableFloor(BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return false;
        BlockState st = world.getBlockState(pos);
        if (!st.getFluidState().isEmpty()) return false;
        if (st.getBlock() == Blocks.MAGMA_BLOCK) return false;
        if (st.getBlock() == Blocks.CACTUS) return false;
        if (st.getBlock() == Blocks.HONEY_BLOCK) return false;
        if (st.getBlock() == Blocks.SOUL_SAND) return false;
        // Need a (near-)full top face to stand on. This excludes fences (1.5h with thin
        // top), end rods (tiny), and other partial blocks that would let the player slip.
        if (st.getCollisionShape(world, pos).isEmpty()) return false;
        return st.getCollisionShape(world, pos).getMax(Direction.Axis.Y) >= 1.0 - 1.0e-3;
    }

    public BlockPos snapToFloor(BlockPos pos, int maxDrop) {
        if (canStand(pos)) return pos;
        for (int i = 1; i <= maxDrop; i++) {
            BlockPos p = pos.down(i);
            if (canStand(p)) return p;
        }
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
