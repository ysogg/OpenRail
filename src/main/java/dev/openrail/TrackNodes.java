package dev.openrail;

/**
 * Network nodes: {@code station:<sign block>} or {@code junction:<junction rail block>}.
 */
final class TrackNodes {

    static final String STATION = "station:";
    static final String JUNCTION = "junction:";

    private final StationRegistry stations;
    private final JunctionRegistry junctions;

    TrackNodes(StationRegistry stations, JunctionRegistry junctions) {
        this.stations = stations;
        this.junctions = junctions;
    }

    static String station(BlockKey sign) {
        return STATION + sign;
    }

    static String junction(BlockKey rail) {
        return JUNCTION + rail;
    }

    static boolean isJunction(String node) {
        return node.startsWith(JUNCTION);
    }

    static BlockKey location(String node) {
        int colon = node.indexOf(':');
        return colon < 0 ? null : BlockKey.parse(node.substring(colon + 1));
    }

    boolean exists(String node) {
        BlockKey key = location(node);
        if (key == null) {
            return false;
        }
        if (node.startsWith(STATION)) {
            return stations.at(key) != null;
        }
        return isJunction(node) && junctions.isJunctionRail(key);
    }

    /** Human-readable name, e.g. "Mall", "Junction: Eastgate" or "Junction at 120, -40". */
    String displayName(String node) {
        BlockKey key = location(node);
        if (key == null) {
            return node;
        }
        if (node.startsWith(STATION)) {
            Station s = stations.at(key);
            return s != null ? s.name() : "Removed station";
        }
        JunctionRegistry.Junction j = junctions.at(key);
        if (j == null) {
            return "Removed junction";
        }
        return j.label().isEmpty() ? "Junction at " + key.x() + ", " + key.z() : "Junction: " + j.label();
    }
}
