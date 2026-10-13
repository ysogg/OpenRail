package dev.openrail;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Chunk;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

/** Keeps {@link JunctionRegistry} and {@link StationRegistry} in step with the world. */
public final class SignIndexListener implements Listener {

    private final Plugin plugin;
    private final JunctionRegistry junctions;
    private final StationRegistry stations;

    public SignIndexListener(Plugin plugin, JunctionRegistry junctions, StationRegistry stations) {
        this.plugin = plugin;
        this.junctions = junctions;
        this.stations = stations;
    }

    /** A junction rail is a plain rail directly under the sign (powered/detector/activator rails can't curve). */
    static boolean isJunctionRail(Block rail) {
        return OxidationState.blockId(rail.getType()).equals("rail"); // by vanilla ID, like copper
    }

    /** Re-reads a sign block and records (or forgets) it. */
    public void refresh(Block block) {
        StationSign.read(block).ifPresentOrElse(s -> stations.put(block, s), () -> stations.remove(block));

        Block rail = block.getRelative(BlockFace.DOWN);
        Optional<JunctionSign> junction = isJunctionRail(rail) ? JunctionSign.read(block) : Optional.empty();
        if (junction.isPresent()) {
            junctions.put(BlockKey.of(rail), new JunctionRegistry.Junction(BlockKey.of(block), junction.get().label()));
        } else {
            JunctionRegistry.Junction existing = junctions.at(BlockKey.of(rail));
            if (existing != null && existing.sign().equals(BlockKey.of(block))) {
                junctions.remove(BlockKey.of(rail));
            }
        }
    }

    public void scanLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scan(chunk);
            }
        }
    }

    private void scan(Chunk chunk) {
        Map<BlockKey, JunctionRegistry.Junction> foundJunctions = new HashMap<>();
        Map<BlockKey, StationSign> foundStations = new HashMap<>();
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Sign)) {
                continue;
            }
            Block block = state.getBlock();
            Optional<StationSign> station = StationSign.read(block);
            if (station.isPresent()) {
                foundStations.put(BlockKey.of(block), station.get());
                continue;
            }
            Block rail = block.getRelative(BlockFace.DOWN);
            if (isJunctionRail(rail)) {
                JunctionSign.read(block).ifPresent(j ->
                    foundJunctions.put(BlockKey.of(rail), new JunctionRegistry.Junction(BlockKey.of(block), j.label())));
            }
        }
        BlockKey.ChunkKey key = new BlockKey.ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        junctions.syncChunk(key, foundJunctions);
        stations.syncChunk(key, foundStations);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            scan(event.getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // The edit is applied after the event; read the finished sign (both sides) a tick later.
        Block block = event.getBlock();
        plugin.getServer().getScheduler().runTask(plugin, () -> refresh(block));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        forget(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        // A rail put back under a [junction] sign (e.g. after swapping it out) makes it a junction again.
        Block above = event.getBlock().getRelative(BlockFace.UP);
        if (Tag.ALL_SIGNS.isTagged(above.getType())) {
            refresh(above);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::forget);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::forget);
    }

    /** A broken block may be a station sign, a junction sign, or a junction rail. */
    private void forget(Block block) {
        stations.remove(block);
        junctions.remove(BlockKey.of(block));                               // the junction rail itself
        BlockKey below = BlockKey.of(block.getRelative(BlockFace.DOWN));   // or its sign
        JunctionRegistry.Junction j = junctions.at(below);
        if (j != null && j.sign().equals(BlockKey.of(block))) {
            junctions.remove(below);
        }
    }
}
