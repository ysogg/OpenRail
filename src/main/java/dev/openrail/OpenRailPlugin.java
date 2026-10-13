package dev.openrail;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class OpenRailPlugin extends JavaPlugin {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60;

    private JunctionRegistry junctions;
    private StationRegistry stations;
    private TrackGraph tracks;
    private MapOverlay overlay;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        DestinationStore store = new DestinationStore(this);
        junctions = new JunctionRegistry(this, getDataFolder());
        junctions.load();
        stations = new StationRegistry(this, getDataFolder());
        stations.load();

        CopperSpeedSettings copper = CopperSpeedSettings.load(getConfig().getConfigurationSection("copper-speed"), getLogger());
        TrackNodes nodes = new TrackNodes(stations, junctions);
        tracks = new TrackGraph(getDataFolder(), getLogger(), new RailTracer(junctions, stations, copper), this::allNodes);
        tracks.load();
        // Signs placed or removed: follow the track out of new stations and junctions, and on past removed ones
        // (checked a tick later, once edits settle).
        boolean[] syncQueued = {false};
        Runnable syncNodes = () -> {
            if (!syncQueued[0]) {
                syncQueued[0] = true;
                getServer().getScheduler().runTask(this, () -> {
                    syncQueued[0] = false;
                    tracks.syncNodes();
                });
            }
        };
        stations.addListener(syncNodes);
        junctions.addListener(syncNodes);
        Router router = new Router(tracks, copper, Math.max(0, getConfig().getDouble("routing.tie-tolerance-seconds", 0.5)));

        TripPlanner planner = new TripPlanner(router, stations, junctions);
        command("dest", new DestCommand(store, stations, planner));
        command("stations", new StationsCommand(stations));

        SignIndexListener index = new SignIndexListener(this, junctions, stations);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new SignListener(stations), this);
        pm.registerEvents(index, this);
        pm.registerEvents(new BoardingListener(store, planner, tracks), this);
        pm.registerEvents(new JunctionSwitcher(junctions, store, planner), this);

        pm.registerEvents(new CopperSpeedListener(this, copper), this);
        CopperCommand copperCommand = new CopperCommand(this, copper);
        command("copperrails", copperCommand);
        command("coppermultiplier", copperCommand);

        pm.registerEvents(new ArrivalListener(store, stations, planner,
            getConfig().getBoolean("arrival.clear-destination", true)), this);
        pm.registerEvents(new TrackWatcher(this, tracks), this);
        command("railmap", new RailMapCommand(tracks, nodes, copper));

        index.scanLoadedChunks();
        tracks.syncNodes();
        startMapOverlay(pm, copper);

        getServer().getScheduler().runTaskTimer(this, this::autosave, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    /** The track network can be large, so it's written off the server thread. */
    private void autosave() {
        junctions.saveIfDirty();
        stations.saveIfDirty();
        Runnable writeTracks = tracks.saveTask();
        if (writeTracks != null) {
            getServer().getScheduler().runTaskAsynchronously(this, writeTracks);
        }
    }

    private void startMapOverlay(PluginManager pm, CopperSpeedSettings copper) {
        if (pm.getPlugin("BlueMap") == null || !getConfig().getBoolean("bluemap.enabled", true)) {
            return;
        }
        try {
            TrackGraph lines = getConfig().getBoolean("bluemap.show-lines", true) ? tracks : null;
            overlay = MapOverlays.blueMap(this, stations, junctions, lines, copper, getConfig().getConfigurationSection("bluemap"));
            overlay.start();
            getLogger().info("BlueMap found: showing stations and junctions on the map.");
        } catch (Throwable t) { // e.g. an incompatible BlueMap API version
            overlay = null;
            getLogger().log(Level.WARNING, "Could not hook into BlueMap; map markers are disabled.", t);
        }
    }

    private void command(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void save() {
        junctions.saveIfDirty();
        stations.saveIfDirty();
        tracks.saveIfDirty();
    }

    /** Every station and junction, as network nodes. */
    private List<String> allNodes() {
        List<String> all = new ArrayList<>();
        junctions.junctions().keySet().forEach(rail -> all.add(TrackNodes.junction(rail)));
        stations.all().forEach(s -> all.add(TrackNodes.station(s.location())));
        return all;
    }

    @Override
    public void onDisable() {
        if (overlay != null) {
            overlay.shutdown();
        }
        if (junctions != null) {
            save();
        }
    }
}
