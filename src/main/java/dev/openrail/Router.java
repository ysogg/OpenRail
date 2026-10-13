package dev.openrail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the quickest route between nodes with a bidirectional search: one search grows out
 * from the origin, another grows back from the destination station(s), and the route is found where
 * they meet. Edge weights are top-speed travel times given the copper under each block.
 * <p>
 * Compass heuristic: when two routes take about the same time (within {@code tieTolerance} seconds and 5%
 * of the route time), the one heading closer to the straight line from origin to destination wins. Each search
 * itself is exact, so a near-tie can never compound hop by hop into a longer route.
 * <p>
 * An edge is only used in the directions a cart can actually run along it (see {@link TrackEdge#aToB}), which
 * keeps carts on the right track where builders lay separate tracks for each direction.
 * <p>
 * The network is compiled into arrays when it changes, and answers are cached until then, maintaining speed at scale.
 */
final class Router {

    /** One step of a route: ride {@code edge} from {@code from} to {@code to}, leaving {@code from} by {@code exit}. */
    record Arc(TrackEdge edge, String from, String to, Side exit) {
    }

    /** A route; {@code arcs} is empty when the origin is already a destination. */
    record Route(List<Arc> arcs, double seconds, double travelBlocks) {
    }

    private static final int CACHE_SIZE = 4096;
    private static final Route NO_ROUTE = new Route(List.of(), -1, -1);
    /** Costs this close are the same cost (floating-point noise). */
    private static final double EXACT = 1e-9;
    /** A near-tie is also capped at this fraction of the route's time, so short hops aren't swapped for detours. */
    private static final double TIE_FRACTION = 0.05;

    private final TrackGraph graph;
    private final CopperSpeedSettings copper;
    private final double tieTolerance;
    private final Map<String, Route> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Route> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    // compiled network (null until needed)
    private Compiled net;
    private int version;
    /** For benchmarking: false runs a plain one-direction search instead. */
    boolean bidirectional = true;
    /** Nodes settled by the most recent search (for benchmarking). */
    int lastSettled;

    private static final class Compiled {
        final Map<String, Integer> index = new HashMap<>();
        final List<String> names = new ArrayList<>();
        double[] x;
        double[] z;
        final List<Arc> arcs = new ArrayList<>();
        int[] arcFrom;
        int[] arcTo;
        double[] arcWeight;
        int[][] out;   // node -> arc ids leaving it
        int[][] in;    // node -> arc ids arriving at it
        Object forward;   // reusable Search state (typed Object: Search is an inner class of Router)
        Object backward;

        int id(String node) {
            Integer i = index.get(node);
            if (i == null) {
                i = names.size();
                index.put(node, i);
                names.add(node);
            }
            return i;
        }
    }

    Router(TrackGraph graph, CopperSpeedSettings copper, double tieTolerance) {
        this.graph = graph;
        this.copper = copper;
        this.tieTolerance = tieTolerance;
        graph.addListener(this::invalidate);
        copper.addListener(this::invalidate); // weights depend on copper settings
    }

    void invalidate() {
        net = null;
        cache.clear();
        version++;
    }

    /** Goes up whenever the network or weights change, so callers can tell a stale answer from a current one. */
    int version() {
        return version;
    }

    /** For benchmarking: forget cached answers but keep the compiled network. */
    void invalidateCacheOnly() {
        cache.clear();
    }

    private Compiled compile() {
        Compiled c = new Compiled();
        for (TrackEdge e : graph.edges()) {
            if (e.aToB) {
                addArc(c, e, e.a, e.b);
            }
            if (e.bToA) {
                addArc(c, e, e.b, e.a);
            }
        }
        int n = c.names.size();
        c.x = new double[n];
        c.z = new double[n];
        for (int i = 0; i < n; i++) {
            BlockKey k = TrackNodes.location(c.names.get(i));
            c.x[i] = k == null ? 0 : k.x();
            c.z[i] = k == null ? 0 : k.z();
        }
        int m = c.arcs.size();
        c.arcFrom = new int[m];
        c.arcTo = new int[m];
        c.arcWeight = new double[m];
        int[] outCount = new int[n];
        int[] inCount = new int[n];
        for (int a = 0; a < m; a++) {
            Arc arc = c.arcs.get(a);
            c.arcFrom[a] = c.index.get(arc.from());
            c.arcTo[a] = c.index.get(arc.to());
            c.arcWeight[a] = Math.max(0.05, arc.edge().seconds(copper));
            outCount[c.arcFrom[a]]++;
            inCount[c.arcTo[a]]++;
        }
        c.out = new int[n][];
        c.in = new int[n][];
        for (int i = 0; i < n; i++) {
            c.out[i] = new int[outCount[i]];
            c.in[i] = new int[inCount[i]];
        }
        Arrays.fill(outCount, 0);
        Arrays.fill(inCount, 0);
        for (int a = 0; a < m; a++) {
            c.out[c.arcFrom[a]][outCount[c.arcFrom[a]]++] = a;
            c.in[c.arcTo[a]][inCount[c.arcTo[a]]++] = a;
        }
        return c;
    }

    private static void addArc(Compiled c, TrackEdge e, String from, String to) {
        c.id(from);
        c.id(to);
        c.arcs.add(new Arc(e, from, to, e.exitAt(from)));
    }

    /**
     * Quickest route from {@code origin} to any of {@code targets}, never leaving the origin by
     * {@code noExit} (the side the cart came in on), or null if no track leads there.
     */
    Route find(String origin, Side noExit, Collection<String> targets) {
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.contains(origin)) {
            return new Route(List.of(), 0, 0);
        }
        String key = origin + '|' + noExit + '|' + new TreeSet<>(targets);
        Route cached = cache.get(key);
        if (cached != null) {
            return cached == NO_ROUTE ? null : cached;
        }
        if (net == null) {
            net = compile();
        }
        Route route = search(net, origin, noExit, targets);
        cache.put(key, route == null ? NO_ROUTE : route);
        return route;
    }

    /**
     * Per-direction search state over node ids. Arrays are sized to the network once and reused: only
     * the entries a search touched are reset afterwards, so a search costs what it explores.
     */
    private final class Search {
        final double[] dist;
        final double[] dev;
        final int[] parent;      // arc id used to reach the node (forward: arriving; backward: leaving)
        final boolean[] settled;
        final Heap heap;
        private int[] touched;
        private int touchedCount;

        Search(int n) {
            dist = new double[n];
            dev = new double[n];
            parent = new int[n];
            settled = new boolean[n];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(parent, -1);
            heap = new Heap(Math.max(16, n));
            touched = new int[Math.max(16, n)];
        }

        private void touch(int node) {
            if (dist[node] == Double.POSITIVE_INFINITY) {
                if (touchedCount == touched.length) {
                    touched = Arrays.copyOf(touched, touchedCount * 2);
                }
                touched[touchedCount++] = node;
            }
        }

        void reset() {
            for (int i = 0; i < touchedCount; i++) {
                int node = touched[i];
                dist[node] = Double.POSITIVE_INFINITY;
                dev[node] = 0;
                parent[node] = -1;
                settled[node] = false;
            }
            touchedCount = 0;
            heap.clear();
        }

        void seed(int node) {
            touch(node);
            dist[node] = 0;
            heap.push(0, 0, node);
        }

        /** Cost of the cheapest unsettled node waiting, discarding stale heap entries. */
        double top() {
            while (!heap.isEmpty()) {
                int node = heap.peekNode();
                if (settled[node] || heap.peekCost() > dist[node] + 1e-9) {
                    heap.pop();
                    continue;
                }
                return heap.peekCost();
            }
            return Double.POSITIVE_INFINITY;
        }

        /**
         * Exact shortest-path step: a cheaper way in always replaces the current one, and the compass only
         * breaks exact ties. (Near-ties are settled once, between whole routes, in {@link Router#search}.)
         */
        void relax(int node, double cost, double d, int arc) {
            if (settled[node]) {
                return;
            }
            double current = dist[node];
            if (cost < current - EXACT || (cost <= current + EXACT && d < dev[node])) {
                touch(node);
                dist[node] = cost;
                dev[node] = d;
                parent[node] = arc;
                heap.push(cost, d, node);
            }
        }
    }

    private Route search(Compiled c, String originName, Side noExit, Collection<String> targetNames) {
        Integer originId = c.index.get(originName);
        if (originId == null) {
            return null;
        }
        int origin = originId;
        int n = c.names.size();
        List<Integer> targets = new ArrayList<>(); // a handful at most (stations sharing a name)
        for (String t : targetNames) {
            Integer id = c.index.get(t);
            if (id != null) {
                targets.add(id);
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        // compass line: origin towards the nearest destination
        int nearest = targets.getFirst();
        for (int t : targets) {
            if (Math.hypot(c.x[t] - c.x[origin], c.z[t] - c.z[origin])
                < Math.hypot(c.x[nearest] - c.x[origin], c.z[nearest] - c.z[origin])) {
                nearest = t;
            }
        }
        double dirX = c.x[nearest] - c.x[origin];
        double dirZ = c.z[nearest] - c.z[origin];
        double dirLen = Math.hypot(dirX, dirZ);

        if (c.forward == null) {
            c.forward = new Search(n);
            c.backward = new Search(n);
        }
        Search fwd = (Search) c.forward;
        Search bwd = (Search) c.backward;
        fwd.reset();
        bwd.reset();
        lastSettled = 0;
        fwd.seed(origin);
        for (int t : targets) {
            bwd.seed(t);
        }
        double best = Double.POSITIVE_INFINITY;
        Set<Integer> meetings = new LinkedHashSet<>(); // nodes where the two searches have met

        while (true) {
            double topF = fwd.top();
            double topB = bwd.top();
            // Keep going a little past the quickest meeting, so near-tied routes are found too.
            if (topF == Double.POSITIVE_INFINITY || topB == Double.POSITIVE_INFINITY
                || topF + topB >= best + tolerance(best)) {
                break;
            }
            boolean forward = !bidirectional || topF <= topB;
            Search s = forward ? fwd : bwd;
            Search other = forward ? bwd : fwd;
            int u = s.heap.peekNode();
            double cost = s.heap.peekCost();
            s.heap.pop();
            s.settled[u] = true;
            lastSettled++;
            for (int a : forward ? c.out[u] : c.in[u]) {
                if (c.arcFrom[a] == origin && noExit != null && noExit == c.arcs.get(a).exit()) {
                    continue; // a cart can't reverse out of the junction it's entering
                }
                int next = forward ? c.arcTo[a] : c.arcFrom[a];
                s.relax(next, cost + c.arcWeight[a], deviation(c, a, dirX, dirZ, dirLen), a);
                double total = s.dist[next] + other.dist[next];
                if (total == Double.POSITIVE_INFINITY) {
                    continue;
                }
                meetings.add(next);
                best = Math.min(best, total);
            }
        }
        // The quickest route wins; one only slightly slower (a near-tie) wins instead if it heads closer to the
        // destination. Totals are re-read here because a node's best cost can drop after the searches first meet.
        int meet = -1;
        double meetTotal = Double.POSITIVE_INFINITY;
        double meetAlignment = Double.POSITIVE_INFINITY;
        for (int node : meetings) {
            double total = fwd.dist[node] + bwd.dist[node];
            if (total > best + tolerance(best)) {
                continue;
            }
            double alignment = fwd.dev[node] + bwd.dev[node];
            if (alignment < meetAlignment - EXACT || (alignment <= meetAlignment + EXACT && total < meetTotal)) {
                meet = node;
                meetTotal = total;
                meetAlignment = alignment;
            }
        }
        if (meet < 0) {
            return null;
        }
        List<Arc> route = new ArrayList<>();
        for (int node = meet; node != origin; ) {
            int a = fwd.parent[node];
            route.add(c.arcs.get(a));
            node = c.arcFrom[a];
        }
        Collections.reverse(route);
        for (int node = meet; !targets.contains(node); ) {
            int a = bwd.parent[node];
            route.add(c.arcs.get(a));
            node = c.arcTo[a];
        }
        double seconds = 0;
        double blocks = 0;
        for (Arc arc : route) {
            seconds += Math.max(0.05, arc.edge().seconds(copper));
            blocks += arc.edge().travelBlocks;
        }
        return new Route(List.copyOf(route), seconds, blocks);
    }

    /** How much slower than the quickest ({@code best} seconds) a route may be and still count as tied with it. */
    private double tolerance(double best) {
        return best == Double.POSITIVE_INFINITY ? 0 : Math.min(tieTolerance, best * TIE_FRACTION);
    }

    /** Angle (radians, 0..pi) between an arc's direction of travel and the origin-to-destination line. */
    private static double deviation(Compiled c, int arc, double dirX, double dirZ, double dirLen) {
        double ax = c.x[c.arcTo[arc]] - c.x[c.arcFrom[arc]];
        double az = c.z[c.arcTo[arc]] - c.z[c.arcFrom[arc]];
        double len = Math.hypot(ax, az) * dirLen;
        if (len < 1e-9) {
            return 0;
        }
        return Math.acos(Math.max(-1, Math.min(1, (ax * dirX + az * dirZ) / len)));
    }

    /** Binary min-heap of (cost, deviation, node), ordered by cost then deviation. */
    private static final class Heap {
        private double[] cost;
        private double[] dev;
        private int[] node;
        private int size;

        Heap(int capacity) {
            cost = new double[capacity];
            dev = new double[capacity];
            node = new int[capacity];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }

        double peekCost() {
            return cost[0];
        }

        int peekNode() {
            return node[0];
        }

        void push(double c, double d, int n) {
            if (size == cost.length) {
                cost = Arrays.copyOf(cost, size * 2);
                dev = Arrays.copyOf(dev, size * 2);
                node = Arrays.copyOf(node, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (!less(c, d, cost[p], dev[p])) {
                    break;
                }
                cost[i] = cost[p];
                dev[i] = dev[p];
                node[i] = node[p];
                i = p;
            }
            cost[i] = c;
            dev[i] = d;
            node[i] = n;
        }

        void pop() {
            size--;
            if (size == 0) {
                return;
            }
            double c = cost[size];
            double d = dev[size];
            int n = node[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) {
                    break;
                }
                int r = l + 1;
                int m = r < size && less(cost[r], dev[r], cost[l], dev[l]) ? r : l;
                if (!less(cost[m], dev[m], c, d)) {
                    break;
                }
                cost[i] = cost[m];
                dev[i] = dev[m];
                node[i] = node[m];
                i = m;
            }
            cost[i] = c;
            dev[i] = d;
            node[i] = n;
        }

        private static boolean less(double c1, double d1, double c2, double d2) {
            return c1 < c2 || (c1 == c2 && d1 < d2);
        }
    }
}
