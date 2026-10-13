package dev.openrail;

import org.bukkit.block.data.Rail;

final class RailShapes {

    private RailShapes() {
    }

    /** The flat rail shape joining two sides, or null for the same side. */
    static Rail.Shape connecting(Side a, Side b) {
        if (a == b) {
            return null;
        }
        boolean n = a == Side.NORTH || b == Side.NORTH;
        boolean s = a == Side.SOUTH || b == Side.SOUTH;
        boolean e = a == Side.EAST || b == Side.EAST;
        boolean w = a == Side.WEST || b == Side.WEST;
        if (n && s) {
            return Rail.Shape.NORTH_SOUTH;
        }
        if (e && w) {
            return Rail.Shape.EAST_WEST;
        }
        if (n) {
            return e ? Rail.Shape.NORTH_EAST : Rail.Shape.NORTH_WEST;
        }
        return e ? Rail.Shape.SOUTH_EAST : Rail.Shape.SOUTH_WEST;
    }

    /** The two sides a shape joins (a slope joins its low and high ends). */
    static Side[] sides(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> new Side[] {Side.NORTH, Side.SOUTH};
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> new Side[] {Side.EAST, Side.WEST};
            case NORTH_EAST -> new Side[] {Side.NORTH, Side.EAST};
            case NORTH_WEST -> new Side[] {Side.NORTH, Side.WEST};
            case SOUTH_EAST -> new Side[] {Side.SOUTH, Side.EAST};
            case SOUTH_WEST -> new Side[] {Side.SOUTH, Side.WEST};
        };
    }

    static boolean joins(Rail.Shape shape, Side side) {
        Side[] s = sides(shape);
        return s[0] == side || s[1] == side;
    }

    /** The other side a shape joins {@code side} to, or null if it doesn't touch {@code side}. */
    static Side otherEnd(Rail.Shape shape, Side side) {
        Side[] s = sides(shape);
        return s[0] == side ? s[1] : s[1] == side ? s[0] : null;
    }

    static boolean isAscending(Rail.Shape shape) {
        return shape.name().startsWith("ASCENDING");
    }
}
