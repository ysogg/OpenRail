package dev.openrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Input;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

/**
 * Sets each junction just before a ridden cart reaches it: the junction rail itself, plus the rails attached
 * to it on the side the cart comes in by (its entry arm) and the side it leaves by (its exit arm), so carts
 * arriving from a branch are taken into the junction and leave it towards the right node.
 * <ol>
 *   <li>A junction powered by redstone is left alone, arms included: redstone always wins.</li>
 *   <li>A rider with a destination follows the route planned by {@code /dest} (re-planned only if the
 *       cart ends up off it).</li>
 *   <li>Otherwise (no destination, or no route) the rider steers: holding left or right turns that way,
 *       holding neither goes straight on.</li>
 * </ol>
 * The lookahead follows the track as it is shaped (not just a straight line), so junctions are found
 * round curves and up slopes, and early enough to switch the entry arm before the cart is on it.
 * {@link RailTracer} follows the same rules when it builds the network.
 */
public final class JunctionSwitcher implements Listener {

    /**
     * How many rail blocks ahead to look for a junction (carts move under one block per tick). A junction must
     * be found at least two blocks out for its entry arm to be switched before the cart is on it.
     */
    private static final int LOOKAHEAD = 3;

    private final JunctionRegistry junctions;
    private final DestinationStore store;
    private final TripPlanner planner;

    private final Map<UUID, BlockKey> lastRail = new HashMap<>();
    private final Map<UUID, Side> lastSteering = new HashMap<>();
    private final Map<UUID, String> warnedAt = new HashMap<>();

    /** The side to leave a junction by, and the track beyond it (null when the rider steered). */
    private record Exit(Side side, TrackEdge track) {
    }

    JunctionSwitcher(JunctionRegistry junctions, DestinationStore store, TripPlanner planner) {
        this.junctions = junctions;
        this.store = store;
        this.planner = planner;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) {
            return;
        }
        Player rider = RailUtil.rider(cart);
        if (rider == null) {
            return;
        }
        Block rail = RailUtil.railAt(event.getTo());
        if (rail == null) {
            return;
        }
        BlockKey here = BlockKey.of(rail);
        boolean sameBlock = here.equals(lastRail.put(cart.getUniqueId(), here));
        boolean sameKeys = steering(rider) == lastSteering.put(cart.getUniqueId(), steering(rider));
        if (sameBlock && sameKeys) {
            return; // act once per block, and again whenever the rider starts or stops steering
        }
        Rail.Shape shape = RailUtil.shapeOf(rail);
        Vector v = cart.getVelocity();
        Side out = shape == null ? null : outgoing(shape, v.getX(), v.getZ());
        if (out != null) {
            lookAhead(cart, rider, rail, out);
        }
    }

    /**
     * Follows the track from the cart's rail (leaving it by {@code out}) to the first junction within
     * {@link #LOOKAHEAD} blocks, and switches it. The cart's own rail is never switched: that would be too late.
     */
    private void lookAhead(Minecart cart, Player rider, Block rail, Side out) {
        Block cur = rail;
        Side curIn = null; // side the cart enters cur by (unknown for its own rail)
        for (int k = 1; k <= LOOKAHEAD; k++) {
            Block next = RailUtil.nextRail(cur, out);
            if (next == null) {
                return;
            }
            Side in = out.opposite();
            if (junctions.isJunctionRail(BlockKey.of(next))) {
                // cur is the entry arm; it can still be switched unless the cart is already on it
                approach(cart, rider, next, in, cur == rail ? null : cur, curIn);
                return;
            }
            Rail.Shape shape = RailUtil.shapeOf(next);
            if (shape == null) {
                return;
            }
            Side through = RailShapes.otherEnd(shape, in);
            Side toJunction = RailUtil.junctionBeside(next, in, junctions);
            if (toJunction != null && RailUtil.canAim(next, in, toJunction, junctions) && !powered(next, toJunction)) {
                if (intoJunction(rider, next, in, toJunction, through)) {
                    through = toJunction; // an entry arm we'll switch into the junction on the next step
                } else if (through == null || through == toJunction) {
                    Side past = passBy(next, in, toJunction); // a split beside the junction: keep the cart going past
                    if (past != null) {
                        aim(next, in, past);
                        through = past;
                    }
                }
            }
            if (through == null) {
                return; // rail doesn't join the way the cart comes in: can't tell where it goes
            }
            curIn = in;
            cur = next;
            out = through;
        }
    }

    /**
     * {@code arm} is attached to a junction on side {@code toJunction}, and a cart coming in by {@code in} could be
     * taken into it. Whether it should be: when the junction is the only track on from the arm, when the arm
     * doesn't lead anywhere else as it's shaped, or when the junction is on the rider's planned route.
     * Otherwise the arm is a split away from the junction and the cart goes past.
     */
    private boolean intoJunction(Player rider, Block arm, Side in, Side toJunction, Side through) {
        Block junction = arm.getRelative(toJunction.dx, 0, toJunction.dz);
        if (passBy(arm, in, toJunction) == null) {
            return true;
        }
        List<String> dest = store.get(rider);
        if (!dest.isEmpty()) {
            return planner.expects(rider, dest.getFirst(), TrackNodes.junction(BlockKey.of(junction)));
        }
        return through == toJunction || through == null;
    }

    /** Whether the junction on side {@code toJunction} of {@code arm} is powered (left to redstone, arms included). */
    private static boolean powered(Block arm, Side toJunction) {
        Block junction = arm.getRelative(toJunction.dx, 0, toJunction.dz);
        return junction.isBlockPowered() || junction.isBlockIndirectlyPowered();
    }

    /** The way a split arm can lead past its junction instead of into it, or null. */
    private Side passBy(Block arm, Side in, Side toJunction) {
        for (Side s : Side.values()) {
            if (s != in && s != toJunction && RailUtil.hasRail(arm, s) && RailUtil.canAim(arm, in, s, junctions)) {
                return s;
            }
        }
        return null;
    }

    /**
     * A cart is about to enter {@code junction} from side {@code entry}. Sets the junction rail, then its
     * arms: {@code entryArm} (null if it can't be switched in time), which the cart enters by
     * {@code armFrom}, and the arm on the side it leaves by.
     */
    void approach(Minecart cart, Player rider, Block junction, Side entry, Block entryArm, Side armFrom) {
        if (junction.isBlockPowered() || junction.isBlockIndirectlyPowered()) {
            return; // redstone-controlled junction
        }
        BlockData data = junction.getBlockData();
        if (!(data instanceof Rail rail) || RailShapes.isAscending(rail.getShape())) {
            return;
        }
        Exit exit = chooseExit(cart, rider, junction, entry);
        if (exit == null || exit.side() == entry) {
            return;
        }
        Rail.Shape shape = RailShapes.connecting(entry, exit.side());
        if (shape == null || !rail.getShapes().contains(shape)) {
            return;
        }
        if (rail.getShape() != shape) {
            rail.setShape(shape);
            junction.setBlockData(rail, false); // no physics: keep neighbours from re-shaping it
        }
        if (entryArm != null && armFrom != null) {
            aim(entryArm, armFrom, entry.opposite());
        }
        Block exitArm = junction.getRelative(exit.side().dx, 0, exit.side().dz);
        Side far = farSide(exitArm, exit.side(), exit.track(), TrackNodes.junction(BlockKey.of(junction)));
        if (far != null) {
            aim(exitArm, exit.side().opposite(), far);
        }
    }

    private Exit chooseExit(Minecart cart, Player rider, Block junction, Side entry) {
        String node = TrackNodes.junction(BlockKey.of(junction));
        List<String> dest = store.get(rider);
        if (!dest.isEmpty()) {
            Router.Arc planned = planner.exitAt(rider, dest.getFirst(), node, entry);
            if (planned != null && planned.exit() != null) {
                warnedAt.remove(cart.getUniqueId());
                return new Exit(planned.exit(), planned.edge());
            }
            if (!node.equals(warnedAt.put(cart.getUniqueId(), node))) {
                rider.sendMessage(Component.text("This junction isn't connected to " + dest.getFirst() + " by track. "
                    + "Hold left or right to steer.", NamedTextColor.YELLOW));
            }
        }
        Side steered = steer(rider, junction, entry.opposite());
        return steered == null ? null : new Exit(steered, null);
    }

    /** Rider steering: left/right turn that way if there's track there; otherwise straight on. */
    private static Side steer(Player rider, Block junction, Side heading) {
        Side key = steering(rider);
        Side wanted = key == Side.WEST ? heading.left() : key == Side.EAST ? heading.right() : heading;
        return RailUtil.hasRail(junction, wanted) ? wanted : null;
    }

    /** The steering key held, relative to the rider: WEST for left, EAST for right, null for neither (or both). */
    private static Side steering(Player rider) {
        Input input = rider.getCurrentInput();
        if (input.isLeft() == input.isRight()) {
            return null;
        }
        return input.isLeft() ? Side.WEST : Side.EAST;
    }

    /**
     * Which way the exit arm (on side {@code exit} of the junction) should lead on, or null if it can't be
     * switched. Follows the track the route takes when there is one; otherwise keeps the arm's current way on.
     */
    private Side farSide(Block arm, Side exit, TrackEdge track, String node) {
        Rail.Shape shape = RailUtil.switchableShape(arm, junctions);
        if (shape == null) {
            return null;
        }
        List<Side> options = new ArrayList<>();
        for (Side s : new Side[] {exit, exit.left(), exit.right()}) { // straight on first
            if (RailUtil.hasRail(arm, s)) {
                options.add(s);
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        if (track != null && options.contains(track.armAt(node))) {
            return track.armAt(node);
        }
        Side kept = RailShapes.otherEnd(shape, exit.opposite());
        if (kept != null && options.contains(kept)) {
            return kept; // already leads out of the junction
        }
        for (Side s : RailShapes.sides(shape)) {
            if (options.contains(s)) {
                return s; // keep the end that leads on
            }
        }
        return options.getFirst();
    }

    /** Shapes an arm rail to join sides {@code a} and {@code b}, if it can be switched. */
    private void aim(Block arm, Side a, Side b) {
        if (!RailUtil.canAim(arm, a, b, junctions)) {
            return;
        }
        Rail rail = (Rail) arm.getBlockData();
        Rail.Shape shape = RailShapes.connecting(a, b);
        if (rail.getShape() != shape) {
            rail.setShape(shape);
            arm.setBlockData(rail, false);
        }
    }

    /** The end of a rail's shape the cart is moving towards, or null if it's (nearly) stopped. */
    private static Side outgoing(Rail.Shape shape, double vx, double vz) {
        Side best = null;
        double bestDot = 1e-4;
        for (Side s : RailShapes.sides(shape)) {
            double dot = s.dx * vx + s.dz * vz;
            if (dot > bestDot) {
                best = s;
                bestDot = dot;
            }
        }
        return best;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        forget(event.getVehicle().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        forget(event.getVehicle().getUniqueId());
    }

    private void forget(UUID cart) {
        lastRail.remove(cart);
        lastSteering.remove(cart);
        warnedAt.remove(cart);
    }
}
