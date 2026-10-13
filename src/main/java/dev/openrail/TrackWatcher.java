package dev.openrail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tells the {@link TrackGraph} which blocks changed, so it re-follows only the track around them. Changes are
 * collected and handed over once per tick, after the world has settled (a rail knocked loose by a broken block
 * underneath is gone by then).
 */
final class TrackWatcher implements Listener {

    private final Plugin plugin;
    private final TrackGraph graph;
    private final Set<BlockKey> changed = new LinkedHashSet<>();
    private boolean queued;

    TrackWatcher(Plugin plugin, TrackGraph graph) {
        this.plugin = plugin;
        this.graph = graph;
    }

    private void changed(Block block) {
        changed.add(BlockKey.of(block));
        if (!queued) {
            queued = true;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                queued = false;
                List<BlockKey> blocks = List.copyOf(changed);
                changed.clear();
                graph.blocksChanged(blocks);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            changed(b);
            changed(b.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            changed(b);
            changed(b.getRelative(event.getDirection()));
        }
    }

    /** Water or lava washing rails away. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (Tag.RAILS.isTagged(event.getToBlock().getType())) {
            changed(event.getToBlock());
        }
    }

    /** Blocks changing by themselves, such as copper oxidising. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        changed(event.getBlock());
    }

    /** Blocks changed by entities, such as copper being scraped or waxed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        graph.chunkLoaded(new BlockKey.ChunkKey(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }
}
