package dev.openrail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Every known [station] sign. Maintained the same way as {@link JunctionRegistry} and saved to
 * {@code plugins/OpenRail/stations.yml}.
 */
public final class StationRegistry {

    private final Plugin plugin;
    private final File file;
    private final ChunkedIndex<Station> index = new ChunkedIndex<>();
    /** lower-case name -> stations with that name (more than one means a duplicate) */
    private final Map<String, List<Station>> byName = new HashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private List<String> sortedNames = List.of();
    private boolean dirty;

    public StationRegistry(Plugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.file = new File(dataFolder, "stations.yml");
    }

    public List<String> names() {
        return sortedNames;
    }

    public List<Station> all() {
        return List.copyOf(index.view().values());
    }

    /** Stations with this name (case-insensitive). */
    public List<Station> named(String name) {
        return List.copyOf(byName.getOrDefault(DestinationStore.normalize(name), List.of()));
    }

    public boolean isDuplicate(Station station) {
        return byName.getOrDefault(station.key(), List.of()).size() > 1;
    }

    public Station at(BlockKey key) {
        return index.get(key);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void put(Block signBlock, StationSign sign) {
        put(new Station(BlockKey.of(signBlock), sign.name(), sign.description()));
    }

    void put(Station station) {
        Station old = index.put(station.location(), station);
        if (station.equals(old)) {
            return;
        }
        unname(old);
        name(station);
        if (old == null || !old.key().equals(station.key())) {
            warnIfDuplicate(station);
        }
        changed();
    }

    public void remove(Block signBlock) {
        remove(BlockKey.of(signBlock));
    }

    void remove(BlockKey key) {
        Station old = index.remove(key);
        if (old != null) {
            unname(old);
            changed();
        }
    }

    public void syncChunk(BlockKey.ChunkKey chunk, Map<BlockKey, StationSign> found) {
        for (BlockKey key : index.inChunk(chunk)) {
            if (!found.containsKey(key)) {
                remove(key);
            }
        }
        found.forEach((key, sign) -> put(new Station(key, sign.name(), sign.description())));
    }

    private void name(Station s) {
        byName.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(s);
    }

    private void unname(Station s) {
        if (s == null) {
            return;
        }
        List<Station> list = byName.get(s.key());
        if (list != null) {
            list.removeIf(x -> x.location().equals(s.location()));
            if (list.isEmpty()) {
                byName.remove(s.key());
            }
        }
    }

    private void warnIfDuplicate(Station station) {
        List<Station> same = byName.getOrDefault(station.key(), List.of());
        if (same.size() > 1 && plugin != null) {
            plugin.getLogger().warning("Duplicate station name '" + station.name() + "': " + same.size()
                + " [station] signs use it (" + same.stream().map(s -> s.location().x() + "," + s.location().y()
                + "," + s.location().z()).toList() + ").");
        }
    }

    private void changed() {
        dirty = true;
        sortedNames = List.copyOf(new TreeSet<>(byName.keySet()));
        listeners.forEach(Runnable::run);
    }

    // --- persistence -------------------------------------------------------------------------

    public void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection stations = yaml.getConfigurationSection("stations");
        if (stations == null) {
            return;
        }
        for (String id : stations.getKeys(false)) {
            BlockKey key = BlockKey.parse(id);
            ConfigurationSection entry = stations.getConfigurationSection(id);
            if (key == null || entry == null) {
                continue;
            }
            String name = entry.getString("name", "");
            if (StationSign.validate(name) != null) {
                continue;
            }
            Station station = new Station(key, name, entry.getString("description", ""));
            index.put(key, station);
            name(station);
        }
        sortedNames = List.copyOf(new TreeSet<>(byName.keySet()));
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        index.view().forEach((key, s) -> {
            yaml.set("stations." + key + ".name", s.name());
            yaml.set("stations." + key + ".description", s.description());
        });
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
