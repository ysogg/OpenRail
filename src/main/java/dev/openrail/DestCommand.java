package dev.openrail;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /dest <station>} sets where you're going and plans the quickest route there from the station or
 * junction nearest you; it's planned again from the track once your cart is moving. {@code /dest} on its own
 * clears it.
 */
public final class DestCommand implements TabExecutor {

    private final DestinationStore store;
    private final StationRegistry stations;
    private final TripPlanner planner;

    DestCommand(DestinationStore store, StationRegistry stations, TripPlanner planner) {
        this.store = store;
        this.stations = stations;
        this.planner = planner;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can set a destination.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            store.clear(player);
            planner.clear(player);
            player.sendMessage(Component.text("Destination cleared.", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 1) {
            return false; // usage: one station name
        }
        List<Station> matches = stations.named(args[0]);
        if (matches.isEmpty()) {
            player.sendMessage(Component.text("There's no station called '" + args[0] + "'. Use /stations to list them.",
                NamedTextColor.RED));
            return true;
        }
        Station station = matches.getFirst();
        store.set(player, List.of(station.key()));
        player.sendMessage(Component.text("Destination set to " + station.name() + ".", NamedTextColor.GREEN));
        planner.planFrom(player, station.key()); // searched again from the track once the cart is moving
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = DestinationStore.normalize(args[0]);
        return stations.names().stream().filter(n -> n.startsWith(prefix)).toList();
    }
}
