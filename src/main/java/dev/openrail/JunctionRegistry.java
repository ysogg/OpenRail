package dev.openrail;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Every known junction, keyed by its junction rail (the rail directly under a {@code [junction]} sign).
 * Kept accurate by sign edits, breaks and a rescan of each chunk as it loads. Saved to
 * {@code plugins/OpenRail/junctions.yml}.
 */
public final class JunctionRegistry {

    /** A junction: where its sign is and its optional map label. */
    public record Junction(BlockKey sign, String label) {
    }

    private final Plugin plugin;
    private final File file;
    private final ChunkedIndex<Junction> index = new ChunkedIndex<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean dirty;

    public JunctionRegistry(Plugin plugin, File dataFolder) {
        this.plugin = plugin;
        this.file = dataFolder == null ? null : new File(dataFolder, "junctions.yml");
    }

    /** Junction rails and their signs. */
    public Map<BlockKey, Junction> junctions() {
        return index.view();
    }

    public boolean isJunctionRail(BlockKey rail) {
        return index.get(rail) != null;
    }

    public Junction at(BlockKey rail) {
        return index.get(rail);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void put(BlockKey rail, Junction junction) {
        Junction old = index.put(rail, junction);
        if (!junction.equals(old)) {
            if (old == null) {
                log("Junction added at " + describe(rail));
            }
            changed();
        }
    }

    public void remove(BlockKey rail) {
        if (index.remove(rail) != null) {
            log("Junction removed at " + describe(rail) + " (its sign or plain rail is gone)");
            changed();
        }
    }

    /** Replaces everything known for a chunk with what a scan of it found (rail -> junction). */
    public void syncChunk(BlockKey.ChunkKey chunk, Map<BlockKey, Junction> found) {
        for (BlockKey key : index.inChunk(chunk)) {
            if (!found.containsKey(key)) {
                remove(key);
            }
        }
        found.forEach(this::put);
    }

    private void log(String message) {
        if (plugin != null) {
            plugin.getLogger().info(message);
        }
    }

    private static String describe(BlockKey rail) {
        return rail.x() + ", " + rail.y() + ", " + rail.z();
    }

    private void changed() {
        dirty = true;
        listeners.forEach(Runnable::run);
    }

    public void load() {
        if (file == null || !file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("junctions");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            BlockKey rail = BlockKey.parse(id);
            ConfigurationSection e = section.getConfigurationSection(id);
            if (rail == null || e == null) {
                continue;
            }
            BlockKey sign = BlockKey.parse(e.getString("sign", ""));
            if (sign != null) {
                index.put(rail, new Junction(sign, e.getString("label", "")));
            }
        }
    }

    public void saveIfDirty() {
        if (!dirty || file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        index.view().forEach((rail, j) -> {
            yaml.set("junctions." + rail + ".sign", j.sign().toString());
            yaml.set("junctions." + rail + ".label", j.label());
        });
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
