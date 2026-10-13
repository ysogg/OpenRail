package dev.openrail;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Once a rider with a destination is moving on the track, plans their route again from the station or junction
 * their cart will come to next ({@code /dest} could only guess from what was nearby). Runs before
 * {@link JunctionSwitcher} so the switcher already follows the new plan.
 */
final class BoardingListener implements Listener {

    private final DestinationStore store;
    private final TripPlanner planner;
    private final TrackGraph graph;

    BoardingListener(DestinationStore store, TripPlanner planner, TrackGraph graph) {
        this.store = store;
        this.planner = planner;
        this.graph = graph;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) {
            return;
        }
        Player rider = RailUtil.rider(cart);
        List<String> dest = rider == null ? List.of() : store.get(rider);
        if (dest.isEmpty() || !planner.needsTrackPlan(rider, dest.getFirst())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        Block rail = RailUtil.railAt(to);
        if (rail == null || dx * dx + dz * dz < 1e-6) {
            return;
        }
        List<TrackGraph.Ahead> ahead = graph.ahead(BlockKey.of(rail), dx, dz);
        if (!ahead.isEmpty()) { // otherwise not on a stretch between stations or junctions yet; try further on
            planner.fromTrack(rider, dest.getFirst(), ahead);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart && event.getEntered() instanceof Player player) {
            planner.boarded(player);
        }
    }
}
