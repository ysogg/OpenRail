package dev.openrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /railmap info}: the line you're standing on (everyone).<br>
 * {@code /railmap retrace}: follow every line again from the rails, e.g. after WorldEdit changed track (admin).
 */
public final class RailMapCommand implements TabExecutor {

    private static final double INFO_RANGE = 12;

    private final TrackGraph graph;
    private final TrackNodes nodes;
    private final CopperSpeedSettings copper;

    public RailMapCommand(TrackGraph graph, TrackNodes nodes, CopperSpeedSettings copper) {
        this.graph = graph;
        this.nodes = nodes;
        this.copper = copper;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                if (sender instanceof Player player) {
                    info(player);
                } else {
                    sender.sendMessage(Component.text("Only players can use /railmap info.", NamedTextColor.RED));
                }
            }
            case "retrace" -> {
                if (!sender.hasPermission("openrail.admin")) {
                    sender.sendMessage(Component.text("Only operators can do that.", NamedTextColor.RED));
                    return true;
                }
                graph.retraceAll();
                sender.sendMessage(Component.text("Followed every line again from the rails: " + graph.edges().size()
                    + " lines. Lines running into unloaded areas finish when those areas load.", NamedTextColor.GREEN));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void info(Player player) {
        Location l = player.getLocation();
        TrackEdge e = graph.nearest(l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), INFO_RANGE);
        if (e == null) {
            player.sendMessage(Component.text("No line near you. A line is any track joining two stations or junctions.",
                NamedTextColor.YELLOW));
            return;
        }
        String a = nodes.displayName(e.a);
        String b = nodes.displayName(e.b);
        String arrow = e.aToB && e.bToA ? " <-> " : " -> ";
        String from = e.aToB ? a : b;
        String to = e.aToB ? b : a;
        player.sendMessage(Component.text(from + arrow + to, NamedTextColor.GOLD));
        player.sendMessage(Component.text(String.format("%,d blocks of track. %s.%s", e.railBlocks,
            TravelTime.estimate(e.travelBlocks, copper), e.aToB && e.bToA ? "" : " One way only."), NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("info"));
        if (sender.hasPermission("openrail.admin")) {
            options.add("retrace");
        }
        options.removeIf(o -> !o.startsWith(args[0].toLowerCase(Locale.ROOT)));
        return options;
    }
}
