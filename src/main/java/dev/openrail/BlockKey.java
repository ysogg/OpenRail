package dev.openrail;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** A block position that doesn't hold a world or chunk reference. Stored as "worldUUID:x,y,z". */
public record BlockKey(UUID world, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    /** Parses {@link #toString()} output; null if malformed. */
    public static BlockKey parse(String s) {
        int colon = s.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String[] xyz = s.substring(colon + 1).split(",");
        if (xyz.length != 3) {
            return null;
        }
        try {
            return new BlockKey(UUID.fromString(s.substring(0, colon)),
                Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ChunkKey chunk() {
        return new ChunkKey(world, x >> 4, z >> 4);
    }

    public boolean isIn(World w) {
        return w.getUID().equals(world);
    }

    /** Horizontal distance from a location to the centre of this block. */
    public double horizontalDistance(Location from) {
        double dx = x + 0.5 - from.getX();
        double dz = z + 0.5 - from.getZ();
        return Math.hypot(dx, dz);
    }

    @Override
    public String toString() {
        return world + ":" + x + "," + y + "," + z;
    }

    public record ChunkKey(UUID world, int x, int z) {
    }
}
