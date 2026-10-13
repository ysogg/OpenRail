package dev.openrail;

import java.util.List;
import java.util.UUID;

/**
 * A stretch of track between two nodes (stations or junctions), stored from node {@code a} to {@code b}
 * (a sorts before b). Built from the rails themselves by {@link RailTracer}.
 */
final class TrackEdge {

    /** Index 0 = no copper, then one slot per {@link OxidationState} in declaration order. */
    static final int TIERS = OxidationState.values().length + 1;

    final String id;
    final String a;
    final String b;
    final UUID world;
    List<PathMath.P> points;     // simplified; every point is a real rail block
    int railBlocks;              // rail blocks along the track, both ends included
    double travelBlocks;         // effective distance at top speed (see PathMath.travelBlocks)
    double[] travelByCopper;     // travelBlocks split by the copper found under the track
    Side exitA;                  // side of junction a this track leaves from (null if a is a station)
    Side exitB;                  // side of junction b this track leaves from (null if b is a station)
    Side armA;                   // which way the rail beside junction a leads on along this track (or null)
    Side armB;                   // ... and beside junction b
    boolean aToB;                // a cart can run along it from a to b
    boolean bToA;                // ... and from b to a (false past a one-way merge)

    TrackEdge(String id, String a, String b, UUID world) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.world = world;
        this.travelByCopper = new double[TIERS];
    }

    void setGeometry(List<PathMath.P> simplified, int railBlocks, double[] travelByCopper) {
        this.points = List.copyOf(simplified);
        this.railBlocks = railBlocks;
        this.travelByCopper = travelByCopper.clone();
        double total = 0;
        for (double t : travelByCopper) {
            total += t;
        }
        this.travelBlocks = total;
    }

    boolean touches(String node) {
        return a.equals(node) || b.equals(node);
    }

    String other(String node) {
        return a.equals(node) ? b : a;
    }

    /** Side of {@code node} (one of this edge's ends) that this track leaves from, or null if it's a station. */
    Side exitAt(String node) {
        boolean fromA = a.equals(node);
        Side known = fromA ? exitA : exitB;
        if (known != null || !TrackNodes.isJunction(node) || points == null || points.size() < 2) {
            return known;
        }
        PathMath.P from = fromA ? points.getFirst() : points.getLast();
        PathMath.P to = fromA ? points.get(1) : points.get(points.size() - 2);
        return Side.dominant(to.x() - from.x(), to.z() - from.z());
    }

    /** Which way the rail beside junction {@code node} leads on along this track, or null if unknown. */
    Side armAt(String node) {
        return a.equals(node) ? armA : armB;
    }

    /** Seconds to ride this edge at top speed, given the copper under it and current copper settings. */
    double seconds(CopperSpeedSettings copper) {
        double base = copper.baseMaxSpeed() * 20;
        double total = travelByCopper[0] / base;
        OxidationState[] states = OxidationState.values();
        for (int i = 0; i < states.length; i++) {
            double multiplier = copper.enabled() ? copper.multiplier(states[i]) : 1.0;
            total += travelByCopper[i + 1] / (base * multiplier);
        }
        return total;
    }
}
