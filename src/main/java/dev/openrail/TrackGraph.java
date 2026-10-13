package dev.openrail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The rail network. Every way out of every station and junction is followed along the actual rails by
 * {@link RailTracer}; each stretch that reaches another node becomes an edge for routing. Nobody has to ride
 * the track first.
 *
 * <p>Kept up to date as the world changes: {@link #blocksChanged} re-follows only the stretches running
 * through (or right beside) a changed block, carrying on from just before it, so laying a long line rail by
 * rail costs the same per rail however long the line already is. A stretch that runs into an unloaded chunk
 * waits there and carries on when the chunk loads. Saved to {@code plugins/OpenRail/tracks.yml}.
 */
public final class TrackGraph {

    private static final int FORMAT = 2;

    private final File file;
    private final Logger log;
    private final RailTracer tracer;
    private final Supplier<Collection<String>> allNodes;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, List<RailTracer.Trace>> traces = new HashMap<>(); // port key -> stretches out of it
    private final Map<String, Set<String>> portsByNode = new HashMap<>();
    private final Set<String> knownNodes = new HashSet<>();
    private final BlockIndex index = new BlockIndex();
    private final Map<BlockKey.ChunkKey, Set<String>> blockedPorts = new HashMap<>();
    private final Map<BlockKey.ChunkKey, Set<String>> waitingNodes = new HashMap<>();
    private final Map<RailTracer.Trace, Derived> derived = new IdentityHashMap<>(); // arrived stretch -> its edge
    private final Map<String, Slot> slots = new LinkedHashMap<>(); // edge key -> the stretches making it up
    private final Set<String> touchedEdges = new HashSet<>();      // edge keys changed since the last rebuild
    private List<TrackEdge> edges = List.of();
    private volatile boolean dirty;
    private long snapshots;     // save snapshots taken (server thread)
    private long written;       // newest snapshot written to disk (guarded by write's lock)

    /** What an arrived stretch contributes to an edge, worked out once per stretch. */
    private record Derived(String key, String a, String b, boolean forward, UUID world, List<PathMath.P> points,
                           int railBlocks, double[] travel, Side exitA, Side exitB, Side armA, Side armB) {
    }

    /** The stretches followed along one edge: from a to b, and from b to a. */
    private static final class Slot {
        final List<RailTracer.Trace> forward = new ArrayList<>(1);
        final List<RailTracer.Trace> backward = new ArrayList<>(1);
        TrackEdge edge;
    }

    public TrackGraph(File dataFolder, Logger log, RailTracer tracer, Supplier<Collection<String>> allNodes) {
        this.file = dataFolder == null ? null : new File(dataFolder, "tracks.yml");
        this.log = log;
        this.tracer = tracer;
        this.allNodes = allNodes;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public List<TrackEdge> edges() {
        return edges;
    }

    // --- keeping it up to date ---------------------------------------------------------------

    /** Brings the network in line with the stations and junctions that exist now. */
    public void syncNodes() {
        Set<String> current = new HashSet<>(allNodes.get());
        List<BlockKey> changed = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String node : new ArrayList<>(knownNodes)) {
            if (!current.contains(node)) {
                knownNodes.remove(node);
                dropNode(node);
                changed.add(RailTracer.railOf(node)); // stretches that ended there now run on through
            }
        }
        for (String node : current) {
            if (knownNodes.add(node)) {
                added.add(node);
                changed.add(RailTracer.railOf(node)); // stretches running through it now end there
            }
        }
        if (!changed.isEmpty()) {
            update(changed, added);
        }
    }

    /** Rails (or the copper under them) at or around these blocks may have changed. */
    public void blocksChanged(Collection<BlockKey> blocks) {
        update(blocks, Set.of());
    }

    /** Carries on the stretches (and looks at the nodes) that were waiting for this chunk. */
    public void chunkLoaded(BlockKey.ChunkKey chunk) {
        Set<String> nodes = waitingNodes.remove(chunk);
        Set<String> ports = blockedPorts.remove(chunk);
        if (nodes == null && ports == null) {
            return;
        }
        if (nodes != null) {
            nodes.forEach(this::retraceNode);
        }
        if (ports != null) {
            for (String port : ports) {
                List<RailTracer.Trace> list = traces.get(port);
                if (list == null) {
                    continue;
                }
                for (RailTracer.Trace t : List.copyOf(list)) {
                    if (t.status() == RailTracer.Status.UNLOADED && chunk.equals(t.blocked())) {
                        replace(port, t, tracer.resume(t, t.path().size() - 1, t.path().size()), t.path().size() - 1);
                    }
                }
            }
        }
        rebuildEdges();
    }

    /** Follows every stretch again from scratch (e.g. after the world was edited without events). */
    public void retraceAll() {
        for (String node : new ArrayList<>(knownNodes)) {
            retraceNode(node);
        }
        rebuildEdges();
    }

    /**
     * A change at block p can alter a stretch at any block q with q - p in [-1,1] x [-1,2] x [-1,1]: q's own
     * shape, the rails beside it, or the copper up to two blocks under it.
     */
    private void update(Collection<BlockKey> blocks, Set<String> addedNodes) {
        Map<String, Integer> affected = new HashMap<>(); // port -> first affected position along its stretch
        Map<String, Integer> affectedUpTo = new HashMap<>(); // port -> last affected position
        Set<String> nodes = new HashSet<>(); // nodes whose ways out may have opened or closed
        for (BlockKey p : blocks) {
            if (p == null) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockKey q = new BlockKey(p.world(), p.x() + dx, p.y() + dy, p.z() + dz);
                        for (BlockIndex.Ref ref : index.at(q)) {
                            affected.merge(ref.port(), ref.pos(), Math::min);
                            affectedUpTo.merge(ref.port(), ref.pos(), Math::max);
                        }
                        String node = tracer.nodeAt(q);
                        if (node != null && knownNodes.contains(node)) {
                            nodes.add(node);
                        }
                    }
                }
            }
        }
        affected.forEach((port, pos) -> {
            RailTracer.Port parsed = RailTracer.Port.parse(port);
            if (parsed != null && pos <= 1) {
                nodes.add(parsed.node()); // the rail beside the node changed (or the track beside that)
            }
        });
        if (affected.isEmpty() && nodes.isEmpty() && addedNodes.isEmpty()) {
            rebuildEdges(); // a removed node's own stretches may have gone
            return;
        }
        for (String node : addedNodes) {
            retraceNode(node);
        }
        for (String node : nodes) {
            if (!addedNodes.contains(node)) {
                refreshPorts(node);
            }
        }
        for (Map.Entry<String, Integer> e : affected.entrySet()) {
            String port = e.getKey();
            List<RailTracer.Trace> list = traces.get(port);
            RailTracer.Port parsed = RailTracer.Port.parse(port);
            if (list == null || parsed == null || addedNodes.contains(parsed.node())) {
                continue; // gone, or already followed again with its node
            }
            int keep = e.getValue() - 1;
            int upTo = affectedUpTo.get(port);
            RailTracer.Trace old = list.size() == 1 ? list.getFirst() : null; // split stretches are followed afresh
            if (old == null) {
                retracePort(parsed);
            } else if (keep < 1) {
                replace(port, old, tracer.trace(parsed, old, upTo), 0);
            } else {
                replace(port, old, tracer.resume(old, keep, upTo), keep);
            }
        }
        rebuildEdges();
    }

    private void retraceNode(String node) {
        dropNode(node);
        if (!knownNodes.contains(node)) {
            return;
        }
        BlockKey.ChunkKey waiting = RailTracer.waitingFor(node);
        if (waiting != null) {
            waitingNodes.computeIfAbsent(waiting, k -> new HashSet<>()).add(node);
            return;
        }
        Set<String> keys = new HashSet<>();
        for (RailTracer.Port port : tracer.ports(node)) {
            keys.add(port.key());
            store(port.key(), tracer.trace(port));
        }
        portsByNode.put(node, keys);
    }

    /**
     * Works out a node's ways out again: new ones are followed, closed ones dropped, and the rest left as they
     * are (any change along them is handled where it happened).
     */
    private void refreshPorts(String node) {
        if (RailTracer.waitingFor(node) != null) {
            retraceNode(node);
            return;
        }
        Set<String> old = portsByNode.getOrDefault(node, Set.of());
        Set<String> keys = new HashSet<>();
        for (RailTracer.Port port : tracer.ports(node)) {
            keys.add(port.key());
            if (!old.contains(port.key())) {
                store(port.key(), tracer.trace(port));
            }
        }
        for (String key : old) {
            if (!keys.contains(key)) {
                unstore(key);
            }
        }
        portsByNode.put(node, keys);
    }

    private void retracePort(RailTracer.Port port) {
        unstore(port.key());
        store(port.key(), tracer.trace(port));
    }

    private void dropNode(String node) {
        Set<String> keys = portsByNode.remove(node);
        if (keys != null) {
            keys.forEach(this::unstore);
        }
        waitingNodes.values().forEach(s -> s.remove(node));
    }

    /** Replaces one stretch out of {@code port} by {@code fresh}, which share its blocks up to {@code keep}. */
    private void replace(String port, RailTracer.Trace old, List<RailTracer.Trace> fresh, int keep) {
        List<RailTracer.Trace> list = traces.get(port);
        if (list == null || !list.removeIf(t -> t == old)) {
            return;
        }
        withdraw(old);
        fresh.forEach(this::contribute);
        if (fresh.size() == 1) {
            // Usually the same track with a change somewhere: only re-index the blocks that differ.
            RailTracer.Trace t = fresh.getFirst();
            list.add(t);
            for (int i = keep + 1; i < Math.max(old.path().size(), t.path().size()); i++) {
                PathMath.P was = i < old.path().size() ? old.path().get(i) : null;
                PathMath.P now = i < t.path().size() ? t.path().get(i) : null;
                if (was != null && !was.equals(now)) {
                    index.remove(old.world(), was, port, i);
                }
                if (now != null && !now.equals(was)) {
                    index.add(t.world(), now, port, i);
                }
            }
            index(port, t, t.path().size()); // just notes where it's blocked, if it is
        } else {
            unindex(port, old, keep + 1);
            for (RailTracer.Trace t : fresh) {
                list.add(t);
                index(port, t, keep + 1);
            }
        }
        dirty = true;
    }

    private void store(String port, List<RailTracer.Trace> list) {
        traces.put(port, new ArrayList<>(list));
        for (RailTracer.Trace t : list) {
            index(port, t, 0);
            contribute(t);
        }
        dirty = true;
    }

    private void unstore(String port) {
        List<RailTracer.Trace> list = traces.remove(port);
        if (list != null) {
            for (RailTracer.Trace t : list) {
                unindex(port, t, 0);
                withdraw(t);
            }
            dirty = true;
        }
    }

    private void index(String port, RailTracer.Trace t, int from) {
        for (int i = from; i < t.path().size(); i++) {
            index.add(t.world(), t.path().get(i), port, i);
        }
        if (t.status() == RailTracer.Status.UNLOADED && t.blocked() != null) {
            blockedPorts.computeIfAbsent(t.blocked(), k -> new HashSet<>()).add(port);
        }
    }

    private void unindex(String port, RailTracer.Trace t, int from) {
        for (int i = from; i < t.path().size(); i++) {
            index.remove(t.world(), t.path().get(i), port, i);
        }
    }

    // --- edges -------------------------------------------------------------------------------

    /** Adds a stretch that was just stored to the edge it runs along (if it reached another node). */
    private void contribute(RailTracer.Trace t) {
        if (t.status() != RailTracer.Status.ARRIVED || t.to().equals(t.from().node())) {
            return;
        }
        Derived d = derive(t);
        derived.put(t, d);
        Slot slot = slots.computeIfAbsent(d.key(), k -> new Slot());
        (d.forward() ? slot.forward : slot.backward).add(t);
        touchedEdges.add(d.key());
    }

    /** Takes a stretch that's being dropped off its edge. */
    private void withdraw(RailTracer.Trace t) {
        Derived d = derived.remove(t);
        Slot slot = d == null ? null : slots.get(d.key());
        if (slot != null) {
            (d.forward() ? slot.forward : slot.backward).removeIf(x -> x == t);
            touchedEdges.add(d.key());
        }
    }

    /**
     * One edge per distinct stretch between two nodes. A stretch followed both ways (the same rails from either
     * end) is one two-way edge; one that can only be followed one way (past a merge) is a one-way edge. Only the
     * edges whose stretches changed are looked at; listeners hear only when the network actually changed.
     */
    private void rebuildEdges() {
        boolean changed = false;
        for (String key : touchedEdges) {
            Slot slot = slots.get(key);
            if (slot == null) {
                continue;
            }
            if (slot.forward.isEmpty() && slot.backward.isEmpty()) {
                slots.remove(key);
                changed |= slot.edge != null;
                continue;
            }
            Derived d = derived.get(slot.forward.isEmpty() ? slot.backward.getLast() : slot.forward.getLast());
            TrackEdge e = new TrackEdge(key, d.a(), d.b(), d.world());
            e.setGeometry(d.points(), d.railBlocks(), d.travel());
            e.exitA = d.exitA();
            e.exitB = d.exitB();
            e.armA = d.armA();
            e.armB = d.armB();
            e.aToB = !slot.forward.isEmpty();
            e.bToA = !slot.backward.isEmpty();
            TrackEdge was = slot.edge;
            if (was == null || was.aToB != e.aToB || was.bToA != e.bToA
                || !java.util.Arrays.equals(was.travelByCopper, e.travelByCopper)) {
                slot.edge = e;
                changed = true;
            }
        }
        touchedEdges.clear();
        if (changed) {
            List<TrackEdge> built = new ArrayList<>(slots.size());
            for (Slot slot : slots.values()) {
                built.add(slot.edge);
            }
            edges = Collections.unmodifiableList(built);
            listeners.forEach(Runnable::run);
        }
    }

    private static Derived derive(RailTracer.Trace t) {
        String from = t.from().node();
        boolean forward = from.compareTo(t.to()) <= 0; // every edge is stored a -> b with a sorting first
        List<PathMath.P> path = t.path();
        List<OxidationState> copper = t.copper();
        if (!forward) {
            path = new ArrayList<>(path);
            Collections.reverse(path);
            copper = new ArrayList<>(copper);
            Collections.reverse(copper);
        }
        String a = forward ? from : t.to();
        String b = forward ? t.to() : from;
        int n = path.size();
        long hash = 1125899906842597L;
        for (PathMath.P p : path) {
            hash = 31 * (31 * (31 * hash + p.x()) + p.y()) + p.z();
        }
        int[] kept = PathMath.simplifyIndices(path);
        List<PathMath.P> points = new ArrayList<>();
        for (int i : kept) {
            points.add(path.get(i));
        }
        boolean junctionA = TrackNodes.isJunction(a);
        boolean junctionB = TrackNodes.isJunction(b);
        return new Derived("e" + Long.toHexString(hash) + "-" + n, a, b, forward, t.world(), points, n,
            PathMath.travelByCopper(path, copper, kept),
            junctionA && n > 1 ? step(path.get(0), path.get(1)) : null,
            junctionB && n > 1 ? step(path.get(n - 1), path.get(n - 2)) : null,
            junctionA && n > 2 ? step(path.get(1), path.get(2)) : null,
            junctionB && n > 2 ? step(path.get(n - 2), path.get(n - 3)) : null);
    }

    private static Side step(PathMath.P from, PathMath.P to) {
        return Side.ofStep(to.x() - from.x(), to.z() - from.z());
    }

    /** A station or junction a cart will come to next, the side it comes in by, and how many rails away it is. */
    public record Ahead(String node, Side entry, int blocks) {
    }

    /**
     * Where a cart on {@code rail}, moving by ({@code dx}, {@code dz}), comes to a station or junction next: one
     * answer per way the track can lead on from here. Empty when the rail isn't on any known stretch.
     */
    public List<Ahead> ahead(BlockKey rail, double dx, double dz) {
        List<Ahead> out = new ArrayList<>();
        PathMath.P here = new PathMath.P(rail.x(), rail.y(), rail.z());
        for (BlockIndex.Ref ref : index.at(rail)) {
            List<RailTracer.Trace> list = traces.get(ref.port());
            if (list == null) {
                continue;
            }
            for (RailTracer.Trace t : list) {
                List<PathMath.P> path = t.path();
                int i = ref.pos();
                int last = path.size() - 1;
                if (i > last || !path.get(i).equals(here)) {
                    continue;
                }
                PathMath.P back = path.get(Math.max(0, i - 1));
                PathMath.P on = path.get(Math.min(last, i + 1));
                double along = (on.x() - back.x()) * dx + (on.z() - back.z()) * dz;
                Ahead a = null;
                if (along > 0 && i < last && t.status() == RailTracer.Status.ARRIVED) {
                    a = new Ahead(t.to(), t.entry(), last - i);
                } else if (along < 0 && i > 0) {
                    a = new Ahead(t.from().node(), t.from().side(), i); // back to where the stretch starts
                }
                if (a != null && !out.contains(a)) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    /** The line closest to a point within {@code maxDistance} blocks, or null. */
    public TrackEdge nearest(UUID world, double x, double y, double z, double maxDistance) {
        TrackEdge best = null;
        double bestDistance = maxDistance;
        for (TrackEdge e : edges) {
            if (!e.world.equals(world)) {
                continue;
            }
            double d = PathMath.distanceToLine(x, y, z, e.points);
            if (d <= bestDistance) {
                bestDistance = d;
                best = e;
            }
        }
        return best;
    }

    // --- persistence -------------------------------------------------------------------------

    /** Loads the stretches saved last time. Call {@link #syncNodes} afterwards. */
    public void load() {
        if (file == null || !file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("traces");
        if (yaml.getInt("version", 1) != FORMAT || section == null) {
            if (log != null) {
                log.info("tracks.yml is from an older version; the network will be rebuilt from the rails.");
            }
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            try {
                RailTracer.Trace t = s == null ? null : readTrace(s);
                if (t != null) {
                    traces.computeIfAbsent(t.from().key(), k -> new ArrayList<>()).add(t);
                }
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                if (log != null) {
                    log.warning("Skipping a malformed stretch in tracks.yml (" + id + ").");
                }
            }
        }
        traces.forEach((port, list) -> {
            String node = list.getFirst().from().node();
            knownNodes.add(node);
            portsByNode.computeIfAbsent(node, k -> new HashSet<>()).add(port);
            list.forEach(t -> {
                index(port, t, 0);
                contribute(t);
            });
        });
        rebuildEdges();
        dirty = false;
    }

    /** Saves now, on this thread. */
    public void saveIfDirty() {
        Runnable write = saveTask();
        if (write != null) {
            write.run();
        }
    }

    /**
     * For saving off the server thread: takes a snapshot now (cheap: stretches never change once made) and returns
     * the slow part, writing the file, to run on any thread. Null if nothing changed since the last save.
     */
    public Runnable saveTask() {
        if (!dirty || file == null) {
            return null;
        }
        List<RailTracer.Trace> snapshot = new ArrayList<>();
        traces.values().forEach(snapshot::addAll);
        dirty = false;
        long version = ++snapshots;
        return () -> write(snapshot, version);
    }

    private synchronized void write(List<RailTracer.Trace> snapshot, long version) {
        if (version <= written) {
            return; // a newer snapshot was written already
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", FORMAT);
        int i = 0;
        for (RailTracer.Trace t : snapshot) {
            writeTrace(yaml.createSection("traces." + (i++)), t);
        }
        try {
            // Write beside the file and swap it in, so a crash mid-write never leaves a half-written tracks.yml.
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(tmp.toPath(), yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            written = version;
        } catch (IOException ex) {
            dirty = true; // try again next time
            if (log != null) {
                log.log(Level.WARNING, "Could not save tracks.yml", ex);
            }
        }
    }

    /** Steps are one character each: side and rise (down, level, up) packed as 'a' + side * 3 + rise. */
    private static void writeTrace(ConfigurationSection s, RailTracer.Trace t) {
        s.set("port", t.from().key());
        s.set("status", t.status().name());
        s.set("to", t.to() == null ? "" : t.to());
        s.set("entry", t.entry() == null ? "" : t.entry().name());
        s.set("next", t.next() == null ? "" : t.next().name());
        s.set("blocked", t.blocked() == null ? "" : t.blocked().x() + "," + t.blocked().z());
        s.set("world", t.world().toString());
        s.set("start", t.path().getFirst().toString());
        StringBuilder steps = new StringBuilder();
        StringBuilder copper = new StringBuilder();
        for (int i = 0; i < t.path().size(); i++) {
            if (i > 0) {
                PathMath.P a = t.path().get(i - 1);
                PathMath.P b = t.path().get(i);
                Side side = step(a, b);
                steps.append((char) ('a' + side.ordinal() * 3 + (b.y() - a.y() + 1)));
            }
            OxidationState o = t.copper().get(i);
            copper.append(o == null ? '0' : (char) ('1' + o.ordinal()));
        }
        s.set("steps", steps.toString());
        s.set("copper", copper.toString());
    }

    private static RailTracer.Trace readTrace(ConfigurationSection s) {
        RailTracer.Port port = RailTracer.Port.parse(s.getString("port", ""));
        if (port == null) {
            return null;
        }
        UUID world = UUID.fromString(s.getString("world", ""));
        List<PathMath.P> path = new ArrayList<>();
        PathMath.P at = PathMath.P.parse(s.getString("start", ""));
        path.add(at);
        for (char c : s.getString("steps", "").toCharArray()) {
            int code = c - 'a';
            Side side = Side.values()[code / 3];
            at = new PathMath.P(at.x() + side.dx, at.y() + code % 3 - 1, at.z() + side.dz);
            path.add(at);
        }
        List<OxidationState> copper = new ArrayList<>();
        String tiers = s.getString("copper", "");
        OxidationState[] states = OxidationState.values();
        for (int i = 0; i < path.size(); i++) {
            char c = i < tiers.length() ? tiers.charAt(i) : '0';
            copper.add(c == '0' ? null : states[c - '1']);
        }
        BlockKey.ChunkKey blocked = null;
        String b = s.getString("blocked", "");
        if (!b.isEmpty()) {
            String[] xz = b.split(",");
            blocked = new BlockKey.ChunkKey(world, Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
        }
        String to = s.getString("to", "");
        return new RailTracer.Trace(port, RailTracer.Status.valueOf(s.getString("status", "")),
            to.isEmpty() ? null : to, Side.parse(s.getString("entry", "")), world, List.copyOf(path),
            Collections.unmodifiableList(copper), Side.parse(s.getString("next", "")), blocked);
    }

    /** Which stretches pass through each rail block, and where along them. */
    private static final class BlockIndex {

        record Ref(String port, int pos) {
        }

        private final Map<UUID, Map<Long, List<Ref>>> worlds = new HashMap<>();

        private static long pack(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        void add(UUID world, PathMath.P p, String port, int pos) {
            List<Ref> refs = worlds.computeIfAbsent(world, w -> new HashMap<>())
                .computeIfAbsent(pack(p.x(), p.y(), p.z()), k -> new ArrayList<>(1));
            Ref ref = new Ref(port, pos);
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }

        void remove(UUID world, PathMath.P p, String port, int pos) {
            Map<Long, List<Ref>> blocks = worlds.get(world);
            if (blocks == null) {
                return;
            }
            long key = pack(p.x(), p.y(), p.z());
            List<Ref> refs = blocks.get(key);
            if (refs != null && refs.remove(new Ref(port, pos)) && refs.isEmpty()) {
                blocks.remove(key);
            }
        }

        List<Ref> at(BlockKey b) {
            Map<Long, List<Ref>> blocks = worlds.get(b.world());
            List<Ref> refs = blocks == null ? null : blocks.get(pack(b.x(), b.y(), b.z()));
            return refs == null ? List.of() : List.copyOf(refs);
        }
    }
}
