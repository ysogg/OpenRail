package dev.openrail;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;

final class RailUtil {

    private RailUtil() {
    }

    /** The rail a cart at this location is riding on (its own block, or the one below for slopes), or null. */
    static Block railAt(Location location) {
        Block block = location.getBlock();
        if (Tag.RAILS.isTagged(block.getType())) {
            return block;
        }
        Block below = block.getRelative(BlockFace.DOWN);
        return Tag.RAILS.isTagged(below.getType()) ? below : null;
    }

    /** The first player riding this cart, or null. */
    static Player rider(Minecart cart) {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /** The rail a cart leaving {@code from} by side {@code out} rolls onto (level, up or down), or null. */
    static Block nextRail(Block from, Side out) {
        for (int dy : new int[] {0, 1, -1}) { // level first, then track sloping up or down
            Block b = from.getRelative(out.dx, dy, out.dz);
            if (Tag.RAILS.isTagged(b.getType())) {
                return b;
            }
        }
        return null;
    }

    /** Whether track continues from {@code rail} on that side (level, or sloping up or down). */
    static boolean hasRail(Block rail, Side side) {
        for (int dy = -1; dy <= 1; dy++) {
            if (Tag.RAILS.isTagged(rail.getRelative(side.dx, dy, side.dz).getType())) {
                return true;
            }
        }
        return false;
    }

    static Rail.Shape shapeOf(Block block) {
        return block.getBlockData() instanceof Rail rail ? rail.getShape() : null;
    }

    /** A junction arm's shape, or null if it isn't one OpenRail may switch (another junction, or a slope). */
    static Rail.Shape switchableShape(Block arm, JunctionRegistry junctions) {
        Rail.Shape shape = shapeOf(arm);
        if (shape == null || RailShapes.isAscending(shape) || junctions.isJunctionRail(BlockKey.of(arm))) {
            return null;
        }
        return shape;
    }

    /** Whether a junction arm can be shaped to join sides {@code a} and {@code b}. */
    static boolean canAim(Block arm, Side a, Side b, JunctionRegistry junctions) {
        Rail.Shape shape = RailShapes.connecting(a, b);
        return shape != null && switchableShape(arm, junctions) != null
            && ((Rail) arm.getBlockData()).getShapes().contains(shape);
    }

    /** The side of {@code rail} (other than {@code in}) with a junction rail beside it, or null. */
    static Side junctionBeside(Block rail, Side in, JunctionRegistry junctions) {
        for (Side s : Side.values()) {
            if (s != in && junctions.isJunctionRail(BlockKey.of(rail.getRelative(s.dx, 0, s.dz)))) {
                return s;
            }
        }
        return null;
    }

    /** The first chunk within {@code radius} blocks (horizontally) of x, z that isn't loaded, or null if all are. */
    static BlockKey.ChunkKey unloadedNear(World world, int x, int z, int radius) {
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return new BlockKey.ChunkKey(world.getUID(), cx, cz);
                }
            }
        }
        return null;
    }
}
