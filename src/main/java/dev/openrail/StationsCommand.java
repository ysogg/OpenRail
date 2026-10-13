package dev.openrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /stations [search] [page]}: lists stations in your world, nearest first, with distance and
 * compass direction. Click a station to put {@code /dest <name>} in your chat box.
 */
public final class StationsCommand implements TabExecutor {

    static final int PAGE_SIZE = 8;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final StationRegistry stations;

    public StationsCommand(StationRegistry stations) {
        this.stations = stations;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String search = null;
        int page = 1;
        for (String arg : args) {
            if (arg.matches("\\d+")) {
                page = Math.max(1, Integer.parseInt(arg));
            } else {
                search = DestinationStore.normalize(arg);
            }
        }
        final String filter = search;

        List<Station> matching = new ArrayList<>(stations.all());
        if (filter != null) {
            matching.removeIf(s -> !s.key().contains(filter));
        }

        Location here = sender instanceof Player p ? p.getLocation() : null;
        List<Station> listed;
        int elsewhere = 0;
        if (here != null) {
            listed = new ArrayList<>();
            for (Station s : matching) {
                if (s.location().isIn(here.getWorld())) {
                    listed.add(s);
                } else {
                    elsewhere++;
                }
            }
            listed.sort(Comparator.comparingDouble(s -> s.location().horizontalDistance(here)));
        } else {
            listed = matching;
            listed.sort(Comparator.comparing(Station::key));
        }

        if (listed.isEmpty()) {
            String what = filter == null ? "No stations" : "No stations matching '" + filter + "'";
            sender.sendMessage(Component.text(what + (here != null ? " in this world." : "."), NamedTextColor.YELLOW));
            return true;
        }

        int pages = (listed.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        page = Math.min(page, pages);
        int from = (page - 1) * PAGE_SIZE;

        String title = "Stations" + (filter != null ? " matching '" + filter + "'" : "")
            + (here != null ? ", nearest first" : "") + " (" + listed.size() + ")";
        sender.sendMessage(Component.text(title + (pages > 1 ? "  page " + page + "/" + pages : ""), NamedTextColor.GOLD));

        for (int i = from; i < Math.min(from + PAGE_SIZE, listed.size()); i++) {
            sender.sendMessage(line(i + 1, listed.get(i), here));
        }

        if (page < pages) {
            String next = "/" + label + (filter != null ? " " + filter : "") + " " + (page + 1);
            sender.sendMessage(Component.text("Next page: " + next, NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand(next)));
        }
        if (elsewhere > 0) {
            sender.sendMessage(Component.text("+ " + elsewhere + " station(s) in other worlds.", NamedTextColor.GRAY));
        }
        return true;
    }

    private Component line(int number, Station s, Location here) {
        BlockKey loc = s.location();
        StringBuilder text = new StringBuilder().append(number).append(". ").append(s.name());
        if (here != null) {
            text.append(" - ").append(Math.round(loc.horizontalDistance(here))).append(" blocks ")
                .append(direction(here, loc));
        }
        Component hover = Component.text(loc.x() + ", " + loc.y() + ", " + loc.z()
            + (s.description().isEmpty() ? "" : "\n" + s.description())
            + "\nClick to set /dest " + s.key(), NamedTextColor.GRAY);
        Component line = Component.text(text.toString(), NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(hover))
            .clickEvent(ClickEvent.suggestCommand("/dest " + s.key()));
        if (stations.isDuplicate(s)) {
            line = line.append(Component.text(" (duplicate name)", NamedTextColor.RED));
        }
        return line;
    }

    /** Compass direction from a location to a block (north is -Z, east is +X). */
    static String direction(Location from, BlockKey to) {
        double dx = to.x() + 0.5 - from.getX();
        double dz = to.z() + 0.5 - from.getZ();
        if (Math.abs(dx) < 1 && Math.abs(dz) < 1) {
            return "(here)";
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        int index = (int) Math.floorMod(Math.round(degrees / 45.0), 8L);
        return COMPASS[index];
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return stations.names().stream().filter(n -> n.startsWith(prefix)).toList();
    }
}
