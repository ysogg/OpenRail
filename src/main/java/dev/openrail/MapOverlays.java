package dev.openrail;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/** Creates map integrations. The only class that references optional map plugins' types. */
final class MapOverlays {

    private MapOverlays() {
    }

    static MapOverlay blueMap(Plugin plugin, StationRegistry stations, JunctionRegistry junctions, TrackGraph tracks,
                              CopperSpeedSettings copper, ConfigurationSection config) {
        BlueMapOverlay.Settings settings = new BlueMapOverlay.Settings(
            config == null ? "Rail Stations" : config.getString("stations-label", "Rail Stations"),
            config == null ? "Rail Junctions" : config.getString("junctions-label", "Rail Junctions"),
            config == null || config.getBoolean("show-junctions", true),
            config == null || config.getBoolean("junctions-hidden-by-default", true),
            config == null ? 1500 : config.getDouble("junction-max-distance", 1500),
            config == null ? "Rail Lines" : config.getString("lines-label", "Rail Lines"),
            config == null ? "#c96f35" : config.getString("line-color", "#c96f35"),
            config == null ? 4 : Math.max(1, config.getInt("line-width", 4)));
        return new BlueMapOverlay(plugin, stations, junctions, tracks, copper, settings);
    }
}
