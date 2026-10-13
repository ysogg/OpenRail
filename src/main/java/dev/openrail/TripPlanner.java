package dev.openrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Holds each rider's planned route. {@code /dest} searches from the station or junction nearest the player,
 * and once their cart is moving on the track it's searched again from the station or junction the cart will
 * come to next. Approaching a junction, the rider simply follows the plan. Only if the cart reaches a junction
 * that isn't on the rest of the plan (a deviation) is a new route searched, starting from that junction.
 */
final class TripPlanner {

    /** A stored plan: the junctions to pass, in order, and the arc (side and track) to leave each one by. */
    static final class Plan {
        final String destination;        // station key
        final List<String> junctions = new ArrayList<>();
        final List<Router.Arc> exits = new ArrayList<>();
        final Router.Route route;        // null when no route was found
        int next;                        // index of the next junction expected
        String lastFailedAt;             // junction and entry side a search last failed from (avoid repeating it)
        int failedVersion;               // network version that search ran on (new track may fix it)
        boolean fromTrack;               // searched from where the cart is on the track (not just from nearby)
        int blocksToStart;               // rails from the cart to where the route starts, when fromTrack

        Plan(String destination, Router.Route route) {
            this.destination = destination;
            this.route = route;
            if (route != null) {
                for (Router.Arc arc : route.arcs()) {
                    if (TrackNodes.isJunction(arc.from())) {
                        junctions.add(arc.from());
                        exits.add(arc);
                    }
                }
            }
        }

        /**
         * Position of {@code junction} in the rest of the plan (including the one just passed, since a cart
         * is checked more than once as it approaches), or -1.
         */
        int find(String junction) {
            for (int i = Math.max(0, next - 1); i < junctions.size(); i++) {
                if (junctions.get(i).equals(junction)) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final Router router;
    private final StationRegistry stations;
    private final JunctionRegistry junctions;
    private final Map<UUID, Plan> plans = new HashMap<>();
    private int searches;

    TripPlanner(Router router, StationRegistry stations, JunctionRegistry junctions) {
        this.router = router;
        this.stations = stations;
        this.junctions = junctions;
    }

    /** Number of route searches run so far (for tests and diagnostics). */
    int searches() {
        return searches;
    }

    Plan plan(Player player) {
        return plans.get(player.getUniqueId());
    }

    void clear(Player player) {
        plans.remove(player.getUniqueId());
    }

    /**
     * Called by {@code /dest}: searches from the station or junction nearest the player and stores the plan. It's
     * searched again from the track once their cart is moving ({@link #fromTrack}).
     */
    Plan planFrom(Player player, String destination) {
        String origin = nearestNode(player.getLocation());
        Router.Route route = origin == null ? null : search(origin, null, destination);
        Plan plan = new Plan(destination, route);
        plans.put(player.getUniqueId(), plan);
        return plan;
    }

    /** Whether the rider's plan to {@code destination} still needs searching from where their cart is. */
    boolean needsTrackPlan(Player player, String destination) {
        Plan plan = plans.get(player.getUniqueId());
        return plan == null || !plan.destination.equals(destination) || !plan.fromTrack;
    }

    /** The rider got into a cart: their route is searched again once it's moving on the track. */
    void boarded(Player player) {
        Plan plan = plans.get(player.getUniqueId());
        if (plan != null) {
            plan.fromTrack = false;
        }
    }

    /**
     * Searches again from where the rider's cart is: from each station or junction it can come to next
     * ({@code ahead}, from {@link TrackGraph#ahead}), keeping the shortest whole trip. Stores and returns the plan.
     */
    Plan fromTrack(Player player, String destination, List<TrackGraph.Ahead> ahead) {
        Router.Route best = null;
        TrackGraph.Ahead start = null;
        for (TrackGraph.Ahead a : ahead) {
            Router.Route route = search(a.node(), a.entry(), destination);
            if (route != null && (best == null
                || a.blocks() + route.travelBlocks() < start.blocks() + best.travelBlocks())) {
                best = route;
                start = a;
            }
        }
        Plan plan = new Plan(destination, best);
        plan.fromTrack = true;
        plan.blocksToStart = start == null ? 0 : start.blocks();
        plans.put(player.getUniqueId(), plan);
        return plan;
    }


    /** Whether {@code junction} is on the rest of the player's plan to {@code destination} (never searches). */
    boolean expects(Player player, String destination, String junction) {
        Plan plan = plans.get(player.getUniqueId());
        return plan != null && plan.destination.equals(destination) && plan.find(junction) >= 0;
    }

    /**
     * The arc to leave {@code junction} by when entering it from {@code entry} (its exit side and the track
     * after it), or null if no track leads there. Follows the stored plan; searches again only on a deviation.
     */
    Router.Arc exitAt(Player player, String destination, String junction, Side entry) {
        Plan plan = plans.get(player.getUniqueId());
        if (plan != null && plan.destination.equals(destination)) {
            int i = plan.find(junction);
            Side planned = i >= 0 ? plan.exits.get(i).exit() : null;
            if (planned != null && planned != entry) {
                plan.next = i + 1;
                return plan.exits.get(i);
            }
            // Otherwise the cart came in by the side the plan leaves from, so the plan can't be followed from
            // here: search again as for a deviation.
            if (plan.route == null && plan.junctions.isEmpty() && failKey(junction, entry).equals(plan.lastFailedAt)
                && plan.failedVersion == router.version()) {
                return null; // already searched from here and found nothing; don't repeat it every block
            }
        }
        // Deviation (or no plan yet, e.g. after relogging): search from where the cart actually is.
        Router.Route route = search(junction, entry, destination);
        Plan replanned = new Plan(destination, route);
        replanned.lastFailedAt = route == null ? failKey(junction, entry) : null;
        replanned.failedVersion = router.version();
        replanned.fromTrack = true;
        plans.put(player.getUniqueId(), replanned);
        if (replanned.junctions.isEmpty() || !replanned.junctions.getFirst().equals(junction)) {
            return null;
        }
        replanned.next = 1;
        return replanned.exits.getFirst();
    }

    private static String failKey(String junction, Side entry) {
        return junction + '|' + entry;
    }

    private Router.Route search(String origin, Side noExit, String destination) {
        List<String> targets = new ArrayList<>();
        for (Station s : stations.named(destination)) {
            targets.add(TrackNodes.station(s.location()));
        }
        searches++;
        return router.find(origin, noExit, targets);
    }

    private String nearestNode(Location here) {
        List<String> nodes = new ArrayList<>();
        junctions.junctions().keySet().forEach(rail -> nodes.add(TrackNodes.junction(rail)));
        stations.all().forEach(s -> nodes.add(TrackNodes.station(s.location())));
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (String node : nodes) {
            BlockKey at = TrackNodes.location(node);
            if (at != null && at.isIn(here.getWorld())) {
                double d = Math.hypot(at.x() + 0.5 - here.getX(), at.z() + 0.5 - here.getZ());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = node;
                }
            }
        }
        return best;
    }
}
