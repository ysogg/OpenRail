package dev.openrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;
import de.bluecolored.bluemap.api.markers.POIMarker;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Shows stations, junctions and the track between them on BlueMap as toggleable marker sets.
 * <p>
 * BlueMap doesn't save markers added through its API, and it fires its enable callback again after
 * every reload ({@code /bluemap reload}), so the sets are rebuilt from our registries each time BlueMap
 * starts. While it runs, any registry change triggers a rebuild about a second later (bursts such as a
 * chunk scan are coalesced).
 */
final class BlueMapOverlay implements MapOverlay {

    static final String STATIONS_SET = "openrail-stations";
    static final String JUNCTIONS_SET = "openrail-junctions";
    static final String LINES_SET = "openrail-lines";
    private static final long REFRESH_DELAY_TICKS = 20L;

    private final Plugin plugin;
    private final StationRegistry stations;
    private final JunctionRegistry junctions;
    private final TrackGraph tracks;
    private final TrackNodes nodes;
    private final CopperSpeedSettings copper;
    private final Settings settings;

    private final Consumer<BlueMapAPI> onEnable = this::onBlueMapEnable;
    private final Consumer<BlueMapAPI> onDisable = this::onBlueMapDisable;

    private volatile BlueMapAPI api;
    private boolean refreshQueued;
    private boolean running;

    record Settings(String stationsLabel, String junctionsLabel, boolean showJunctions,
                    boolean junctionsHiddenByDefault, double junctionMaxDistance,
                    String linesLabel, String lineColor, int lineWidth) {
    }

    BlueMapOverlay(Plugin plugin, StationRegistry stations, JunctionRegistry junctions, TrackGraph tracks,
                   CopperSpeedSettings copper, Settings settings) {
        this.plugin = plugin;
        this.stations = stations;
        this.junctions = junctions;
        this.tracks = tracks;
        this.nodes = new TrackNodes(stations, junctions);
        this.copper = copper;
        this.settings = settings;
    }

    @Override
    public void start() {
        running = true;
        stations.addListener(this::requestRefresh);
        junctions.addListener(this::requestRefresh);
        if (tracks != null) {
            tracks.addListener(this::requestRefresh);
        }
        copper.addListener(this::requestRefresh); // travel-time estimates depend on copper settings
        BlueMapAPI.onDisable(onDisable);
        BlueMapAPI.onEnable(onEnable); // runs immediately if BlueMap is already up
    }

    @Override
    public void shutdown() {
        running = false;
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
        BlueMapAPI current = api;
        if (current != null) {
            for (BlueMapMap map : current.getMaps()) {
                map.getMarkerSets().remove(STATIONS_SET);
                map.getMarkerSets().remove(JUNCTIONS_SET);
                map.getMarkerSets().remove(LINES_SET);
            }
        }
        api = null;
    }

    private void onBlueMapEnable(BlueMapAPI enabled) {
        api = enabled;
        // BlueMap usually calls this from its own thread; read our registries on the server thread.
        plugin.getServer().getScheduler().runTask(plugin, this::rebuild);
    }

    private void onBlueMapDisable(BlueMapAPI disabled) {
        api = null;
    }

    private void requestRefresh() {
        if (!running || api == null || refreshQueued) {
            return;
        }
        refreshQueued = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            refreshQueued = false;
            rebuild();
        }, REFRESH_DELAY_TICKS);
    }

    /** Replaces our markers on every BlueMap map with the current registry contents. Server thread. */
    void rebuild() {
        BlueMapAPI current = api;
        if (!running || current == null) {
            return;
        }
        Map<UUID, List<Station>> stationsByWorld = new HashMap<>();
        for (Station s : stations.all()) {
            stationsByWorld.computeIfAbsent(s.location().world(), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, Map<BlockKey, JunctionRegistry.Junction>> junctionsByWorld = new HashMap<>();
        junctions.junctions().forEach((key, j) ->
            junctionsByWorld.computeIfAbsent(key.world(), k -> new HashMap<>()).put(key, j));

        Map<UUID, List<TrackEdge>> edgesByWorld = new HashMap<>();
        if (tracks != null) {
            for (TrackEdge e : tracks.edges()) {
                edgesByWorld.computeIfAbsent(e.world, k -> new ArrayList<>()).add(e);
            }
        }

        for (World world : plugin.getServer().getWorlds()) {
            BlueMapWorld blueWorld = current.getWorld(world).orElse(null);
            if (blueWorld == null) {
                continue;
            }
            List<Station> worldStations = stationsByWorld.getOrDefault(world.getUID(), List.of());
            Map<BlockKey, JunctionRegistry.Junction> worldJunctions = junctionsByWorld.getOrDefault(world.getUID(), Map.of());
            List<TrackEdge> worldEdges = edgesByWorld.getOrDefault(world.getUID(), List.of());
            for (BlueMapMap map : blueWorld.getMaps()) {
                fillStations(map, worldStations);
                if (tracks != null) {
                    fillLines(map, worldEdges);
                }
                if (settings.showJunctions()) {
                    fillJunctions(map, worldJunctions);
                } else {
                    map.getMarkerSets().remove(JUNCTIONS_SET);
                }
            }
        }
    }

    private void fillStations(BlueMapMap map, List<Station> list) {
        MarkerSet set = map.getMarkerSets().computeIfAbsent(STATIONS_SET, id -> MarkerSet.builder()
            .label(settings.stationsLabel()).toggleable(true).defaultHidden(false).sorting(0).build());
        set.getMarkers().clear();
        for (Station s : list) {
            BlockKey loc = s.location();
            StringBuilder detail = new StringBuilder()
                .append("<b>").append(html(s.name())).append("</b>");
            if (!s.description().isEmpty()) {
                detail.append("<br>").append(html(s.description()));
            }
            detail.append("<br><code>/dest ").append(html(s.key())).append("</code>")
                .append("<br><small>").append(loc.x()).append(", ").append(loc.y()).append(", ").append(loc.z()).append("</small>");
            if (stations.isDuplicate(s)) {
                detail.append("<br><i>Another station uses this name.</i>");
            }
            set.put("station-" + markerId(loc), POIMarker.builder()
                .label(s.name())
                .position(center(loc))
                .detail(detail.toString())
                .build());
        }
    }

    private void fillJunctions(BlueMapMap map, Map<BlockKey, JunctionRegistry.Junction> list) {
        MarkerSet set = map.getMarkerSets().computeIfAbsent(JUNCTIONS_SET, id -> MarkerSet.builder()
            .label(settings.junctionsLabel()).toggleable(true).defaultHidden(settings.junctionsHiddenByDefault())
            .sorting(1).build());
        set.getMarkers().clear();
        list.forEach((rail, j) -> {
            String node = TrackNodes.junction(rail);
            List<String> connected = new ArrayList<>();
            if (tracks != null) {
                for (TrackEdge e : tracks.edges()) {
                    if (e.touches(node)) {
                        connected.add(nodes.displayName(e.other(node)));
                    }
                }
            }
            String name = nodes.displayName(node);
            StringBuilder detail = new StringBuilder("<b>").append(html(name)).append("</b><br>");
            detail.append(connected.isEmpty() ? "Not connected to anything by track yet."
                : "Connects to: " + html(String.join(", ", new TreeSet<>(connected))));
            detail.append("<br><small>").append(rail.x()).append(", ").append(rail.y()).append(", ").append(rail.z()).append("</small>");
            POIMarker.Builder marker = POIMarker.builder()
                .label(name)
                .position(center(rail))
                .detail(detail.toString());
            if (settings.junctionMaxDistance() > 0) {
                marker.maxDistance(settings.junctionMaxDistance());
            }
            set.put("junction-" + markerId(rail), marker.build());
        });
    }

    private void fillLines(BlueMapMap map, List<TrackEdge> edges) {
        MarkerSet set = map.getMarkerSets().computeIfAbsent(LINES_SET, id -> MarkerSet.builder()
            .label(settings.linesLabel()).toggleable(true).defaultHidden(false).sorting(2).build());
        set.getMarkers().clear();
        Color color = new Color(settings.lineColor());
        for (TrackEdge e : edges) {
            List<Vector3d> points = new ArrayList<>();
            for (PathMath.P p : e.points) {
                points.add(new Vector3d(p.x() + 0.5, p.y() + 0.3, p.z() + 0.5)); // just above the rails
            }
            String from = nodes.displayName(e.a);
            String to = nodes.displayName(e.b);
            String time = TravelTime.estimate(e.travelBlocks, copper);
            String detail = "<b>" + html(from) + " &harr; " + html(to) + "</b>"
                + "<br>" + String.format("%,d", e.railBlocks) + " blocks of track"
                + "<br>" + html(time)
                + (e.aToB && e.bToA ? "" : "<br><small>One way only</small>");
            set.put("line-" + e.id, LineMarker.builder()
                .label(from + " \u2194 " + to + " (" + time + ")")
                .line(new Line(points))
                .detail(detail)
                .lineColor(color)
                .lineWidth(settings.lineWidth())
                .depthTestEnabled(false)
                .build());
        }
    }

    private static Vector3d center(BlockKey loc) {
        return new Vector3d(loc.x() + 0.5, loc.y() + 0.5, loc.z() + 0.5);
    }

    private static String markerId(BlockKey loc) {
        return loc.x() + "_" + loc.y() + "_" + loc.z();
    }

    static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
