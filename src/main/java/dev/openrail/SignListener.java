package dev.openrail;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/** Gates junction and station sign creation behind permissions and gives builders feedback. */
public final class SignListener implements Listener {

    private final StationRegistry stations;

    public SignListener(StationRegistry stations) {
        this.stations = stations;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        List<String> lines = event.lines().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        if (lines.isEmpty()) {
            return;
        }
        if (StationSign.isHeader(lines.getFirst())) {
            onStationSign(event, lines);
        } else if (JunctionSign.isHeader(lines.getFirst())) {
            onJunctionSign(event);
        }
    }

    private void onJunctionSign(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("openrail.junction.create")) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You don't have permission to create junctions.", NamedTextColor.RED));
            return;
        }
        if (!SignIndexListener.isJunctionRail(event.getBlock().getRelative(BlockFace.DOWN))) {
            player.sendMessage(Component.text("A [junction] sign must sit directly above a plain rail (not powered, detector "
                + "or activator rail): that rail is the one OpenRail switches.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Junction registered.", NamedTextColor.GREEN));
    }

    private void onStationSign(SignChangeEvent event, List<String> lines) {
        Player player = event.getPlayer();
        if (!player.hasPermission("openrail.station.create")) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You don't have permission to create stations.", NamedTextColor.RED));
            return;
        }
        StationSign.Problem problem = StationSign.validate(lines.size() > 1 ? lines.get(1) : "");
        if (problem != null) {
            player.sendMessage(Component.text("Not a valid station sign: " + problem.message, NamedTextColor.RED));
            return;
        }
        StationSign sign = StationSign.parse(lines).orElseThrow();
        if (!Tag.RAILS.isTagged(event.getBlock().getRelative(BlockFace.DOWN).getType())) {
            player.sendMessage(Component.text("A [station] sign must sit directly above a rail: carts arrive at '"
                + sign.name() + "' only on the rail under the sign.", NamedTextColor.YELLOW));
        }
        BlockKey here = BlockKey.of(event.getBlock());
        List<Station> others = stations.named(sign.name()).stream()
            .filter(s -> !s.location().equals(here)).toList();
        if (others.isEmpty()) {
            player.sendMessage(Component.text("Station '" + sign.name() + "' registered. Players can travel here with /dest "
                + sign.key(), NamedTextColor.GREEN));
            return;
        }
        Station other = others.getFirst();
        String where = other.location().x() + ", " + other.location().y() + ", " + other.location().z();
        String distance = other.location().world().equals(here.world())
            ? " (" + Math.round(other.location().horizontalDistance(event.getBlock().getLocation())) + " blocks away)"
            : " (in another world)";
        player.sendMessage(Component.text("Warning: a station named '" + other.name() + "' already exists at " + where + distance
            + (others.size() > 1 ? " and " + (others.size() - 1) + " other place(s)" : "")
            + ". /dest " + sign.key() + " will route to whichever is quickest to reach. Consider a unique name.",
            NamedTextColor.GOLD));
    }
}
