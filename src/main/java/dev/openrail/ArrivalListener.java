package dev.openrail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Tells a rider they've arrived, and clears their destination, when their cart reaches the rail directly under
 * the sign of the station they were heading for. Passing nearby doesn't count.
 */
final class ArrivalListener implements Listener {

    private final DestinationStore store;
    private final StationRegistry stations;
    private final TripPlanner planner;
    private final boolean clearOnArrival;
    private final Map<UUID, String> lastStation = new HashMap<>(); // cart -> station it's at (announce once per visit)

    ArrivalListener(DestinationStore store, StationRegistry stations, TripPlanner planner, boolean clearOnArrival) {
        this.store = store;
        this.stations = stations;
        this.planner = planner;
        this.clearOnArrival = clearOnArrival;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(VehicleMoveEvent event) {
        if (!clearOnArrival || !(event.getVehicle() instanceof Minecart cart)) {
            return;
        }
        Player rider = RailUtil.rider(cart);
        Block rail = rider == null ? null : RailUtil.railAt(event.getTo());
        Station station = rail == null ? null
            : stations.at(new BlockKey(rail.getWorld().getUID(), rail.getX(), rail.getY() + 1, rail.getZ()));
        if (station == null) {
            lastStation.remove(cart.getUniqueId());
            return;
        }
        if (station.key().equals(lastStation.put(cart.getUniqueId(), station.key()))) {
            return;
        }
        List<String> destinations = store.get(rider);
        if (destinations.isEmpty() || !destinations.getFirst().equals(station.key())) {
            return;
        }
        store.clear(rider);
        planner.clear(rider);
        rider.sendMessage(Component.text("Arrived at " + station.name() + ". Destination cleared.", NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        lastStation.remove(event.getVehicle().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        lastStation.remove(event.getVehicle().getUniqueId());
    }
}
