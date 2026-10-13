package dev.openrail;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Stores each player's chosen destinations on the player itself (persistent data container),
 * so a destination survives relogs and server restarts.
 */
public final class DestinationStore {

    private final NamespacedKey key;

    public DestinationStore(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "destinations");
    }

    /** Returns the player's destinations, normalized to lower case. Empty if none are set. */
    public List<String> get(Player player) {
        String raw = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.trim().split("\\s+")).toList();
    }

    public void set(Player player, List<String> destinations) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (destinations.isEmpty()) {
            pdc.remove(key);
            return;
        }
        String joined = String.join(" ", destinations.stream().map(DestinationStore::normalize).toList());
        pdc.set(key, PersistentDataType.STRING, joined);
    }

    public void clear(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    public static String normalize(String destination) {
        return destination.trim().toLowerCase(Locale.ROOT);
    }
}
