package dev.openrail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;

/**
 * Follows the rails out of a station or junction the way a cart would, to the next station or junction. This is
 * how the network is built: no one has to ride the track first. It matches what {@link JunctionSwitcher} does
 * at junctions: an exit arm is switched to lead on (so each way it can lead on is a separate way out), and an
 * arm a cart reaches from elsewhere is switched into its junction for routes that go through it.
 * Never loads chunks: a trace stops where the world isn't loaded and carries on when it is.
 */
final class RailTracer {

    /** Longest stretch followed between two nodes. */
    static final int MAX_BLOCKS = 50_000;

    /**
     * A way out of a node: the side a cart leaves by and, when the rail beside a junction on that side can be
     * switched, which way that rail leads on (null otherwise).
     */
    record Port(String node, Side side, Side arm) {
        String key() {
            return node + "|" + side + "|" + (arm == null ? "" : arm.name());
        }

        static Port parse(String key) {
            String[] p = key.split("\\|", -1);
            if (p.length != 3 || Side.parse(p[1]) == null) {
                return null;
            }
            return new Port(p[0], Side.parse(p[1]), Side.parse(p[2]));
        }
    }

    enum Status {
        /** Reached another node ({@code to}). */
        ARRIVED,
        /** The track ends, runs into a rail it doesn't join, or loops back on itself. */
        DEAD_END,
        /** Ran into a chunk that isn't loaded ({@code blocked}); carries on from the last block by {@code next}. */
        UNLOADED,
        /** Ran on for more than {@link #MAX_BLOCKS} blocks. */
        TOO_LONG
    }

    /**
     * One stretch followed from {@code from}. {@code path} holds every rail block in order, starting with the
     * node's own rail (and ending with the other node's rail when it arrived); {@code copper.get(i)} is the copper
     * under {@code path.get(i)} (null for none). {@code entry} is the side the cart enters {@code to} by.
     */
    record Trace(Port from, Status status, String to, Side entry, java.util.UUID world, List<PathMath.P> path,
                 List<OxidationState> copper, Side next, BlockKey.ChunkKey blocked) {
    }

    private final JunctionRegistry junctions;
    private final StationRegistry stations;
    private final CopperSpeedSettings copperSettings;

    RailTracer(JunctionRegistry junctions, StationRegistry stations, CopperSpeedSettings copperSettings) {
        this.junctions = junctions;
        this.stations = stations;
        this.copperSettings = copperSettings;
    }

    /** The rail block a node stands for: a junction's rail, or the rail under a station's sign. */
    static BlockKey railOf(String node) {
        BlockKey key = TrackNodes.location(node);
        if (key == null || TrackNodes.isJunction(node)) {
            return key;
        }
        return new BlockKey(key.world(), key.x(), key.y() - 1, key.z());
    }

    /** The node whose rail {@code rail} is, or null. */
    String nodeAt(BlockKey rail) {
        if (junctions.isJunctionRail(rail)) {
            return TrackNodes.junction(rail);
        }
        Station station = stations.at(new BlockKey(rail.world(), rail.x(), rail.y() + 1, rail.z()));
        return station == null ? null : TrackNodes.station(station.location());
    }

    /** The chunk that must load before {@link #ports} can look at {@code node}, or null if it can now. */
    static BlockKey.ChunkKey waitingFor(String node) {
        BlockKey rail = railOf(node);
        World world = rail == null ? null : Bukkit.getWorld(rail.world());
        if (world == null) {
            return null;
        }
        return RailUtil.unloadedNear(world, rail.x(), rail.z(), 2);
    }

    /** Every way out of {@code node}. Call only when {@link #waitingFor} is null. */
    List<Port> ports(String node) {
        List<Port> ports = new ArrayList<>();
        Block rail = block(railOf(node));
        if (rail == null || !Tag.RAILS.isTagged(rail.getType())) {
            return ports;
        }
        if (!TrackNodes.isJunction(node)) {
            Rail.Shape shape = RailUtil.shapeOf(rail);
            if (shape != null) {
                for (Side s : RailShapes.sides(shape)) {
                    if (RailUtil.hasRail(rail, s)) {
                        ports.add(new Port(node, s, null));
                    }
                }
            }
            return ports;
        }
        for (Side s : Side.values()) {
            Block arm = RailUtil.nextRail(rail, s);
            if (arm == null) {
                continue;
            }
            int before = ports.size();
            if (nodeAt(BlockKey.of(arm)) == null && RailUtil.switchableShape(arm, junctions) != null) {
                for (Side on : new Side[] {s, s.left(), s.right()}) { // each way the arm can be switched to lead on
                    if (RailUtil.hasRail(arm, on) && RailUtil.canAim(arm, s.opposite(), on, junctions)) {
                        ports.add(new Port(node, s, on));
                    }
                }
            }
            if (ports.size() == before) {
                ports.add(new Port(node, s, null)); // the arm as it's shaped (or a dead end, watched for track)
            }
        }
        return ports;
    }

    /** Follows the rails out of {@code port}. More than one trace when the track splits beside a junction. */
    List<Trace> trace(Port port) {
        return trace(port, null, 0);
    }

    /**
     * Like {@link #trace(Port)}, but once past block {@code changedUpTo} of {@code old} (the last one a change
     * could affect) and back on its blocks, takes the rest of {@code old} as it is instead of following it again.
     */
    List<Trace> trace(Port port, Trace old, int changedUpTo) {
        List<Trace> out = new ArrayList<>();
        Block start = block(railOf(port.node()));
        if (start == null) {
            return out;
        }
        List<PathMath.P> path = new ArrayList<>();
        List<OxidationState> copper = new ArrayList<>();
        path.add(p(start));
        copper.add(copperSettings.copperUnder(start));
        walk(port, start, port.side(), true, path, copper, new HashSet<>(path), old, changedUpTo, out);
        return out;
    }

    /**
     * Carries on {@code old} from its block {@code keep} (the blocks up to it are kept as they are), leaving it
     * the way the trace did. {@code keep} must be at least 1: the node's own way out is never re-decided here.
     */
    List<Trace> resume(Trace old, int keep, int changedUpTo) {
        List<Trace> out = new ArrayList<>();
        World world = Bukkit.getWorld(old.world());
        if (world == null || keep < 1 || keep >= old.path().size()) {
            return out;
        }
        Side leave;
        if (keep == old.path().size() - 1) {
            leave = old.next();
        } else {
            PathMath.P a = old.path().get(keep);
            PathMath.P b = old.path().get(keep + 1);
            leave = Side.ofStep(b.x() - a.x(), b.z() - a.z());
        }
        if (leave == null) {
            return trace(old.from(), old, changedUpTo);
        }
        List<PathMath.P> path = new ArrayList<>(old.path().subList(0, keep + 1));
        List<OxidationState> copper = new ArrayList<>(old.copper().subList(0, keep + 1));
        PathMath.P at = path.getLast();
        walk(old.from(), world.getBlockAt(at.x(), at.y(), at.z()), leave, false, path, copper, new HashSet<>(path), old,
            changedUpTo, out);
        return out;
    }

    private void walk(Port port, Block cur, Side out, boolean leavingNode, List<PathMath.P> path,
                      List<OxidationState> copper, Set<PathMath.P> seen, Trace old, int changedUpTo, List<Trace> results) {
        World world = cur.getWorld();
        while (true) {
            if (path.size() >= MAX_BLOCKS) {
                results.add(end(port, Status.TOO_LONG, null, null, world, path, copper, null, null));
                return;
            }
            BlockKey.ChunkKey blocked = RailUtil.unloadedNear(world, cur.getX() + out.dx, cur.getZ() + out.dz, 1);
            if (blocked != null) {
                results.add(end(port, Status.UNLOADED, null, null, world, path, copper, out, blocked));
                return;
            }
            Block next = RailUtil.nextRail(cur, out);
            PathMath.P here = next == null ? null : p(next);
            if (next == null || !seen.add(here)) {
                results.add(end(port, Status.DEAD_END, null, null, world, path, copper, null, null));
                return;
            }
            Side in = out.opposite();
            path.add(here);
            copper.add(copperSettings.copperUnder(next));
            int i = path.size() - 1;
            if (old != null && i > changedUpTo && i < old.path().size() && here.equals(old.path().get(i))
                && path.get(i - 1).equals(old.path().get(i - 1))) {
                // Back on the old track past the change, coming in the same way: the rest is as it was.
                path.addAll(old.path().subList(i + 1, old.path().size()));
                copper.addAll(old.copper().subList(i + 1, old.copper().size()));
                results.add(end(port, old.status(), old.to(), old.entry(), world, path, copper, old.next(), old.blocked()));
                return;
            }
            String node = nodeAt(BlockKey.of(next));
            if (node != null) {
                results.add(end(port, Status.ARRIVED, node, in, world, path, copper, null, null));
                return;
            }
            Rail.Shape shape = RailUtil.shapeOf(next);
            Side through = shape == null ? null : RailShapes.otherEnd(shape, in);
            if (leavingNode && port.arm() != null) {
                through = port.arm(); // the exit arm is switched to lead this way
            } else {
                Side toJunction = RailUtil.junctionBeside(next, in, junctions);
                if (toJunction != null && RailUtil.canAim(next, in, toJunction, junctions)) {
                    // An arm of another junction: switched into it for routes through that junction. If the track
                    // also carries on past it, that's another way to go.
                    for (Side s : Side.values()) {
                        if (s != in && s != toJunction && RailUtil.hasRail(next, s) && RailUtil.canAim(next, in, s, junctions)) {
                            walk(port, next, s, false, new ArrayList<>(path), new ArrayList<>(copper), new HashSet<>(seen), old,
                                changedUpTo, results);
                        }
                    }
                    through = toJunction;
                }
            }
            leavingNode = false;
            if (through == null) {
                results.add(end(port, Status.DEAD_END, null, null, world, path, copper, null, null));
                return;
            }
            cur = next;
            out = through;
        }
    }

    private static Trace end(Port port, Status status, String to, Side entry, World world, List<PathMath.P> path,
                             List<OxidationState> copper, Side next, BlockKey.ChunkKey blocked) {
        return new Trace(port, status, to, entry, world.getUID(), List.copyOf(path),
            java.util.Collections.unmodifiableList(new ArrayList<>(copper)), next, blocked);
    }

    private static Block block(BlockKey key) {
        World world = key == null ? null : Bukkit.getWorld(key.world());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static PathMath.P p(Block b) {
        return new PathMath.P(b.getX(), b.getY(), b.getZ());
    }
}
