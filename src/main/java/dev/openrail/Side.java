package dev.openrail;

/** A horizontal side of a block / compass heading. North is -Z, east is +X. */
public enum Side {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    public final int dx;
    public final int dz;

    Side(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public Side opposite() {
        return values()[(ordinal() + 2) % 4];
    }

    /** The side to the left when facing this way. */
    public Side left() {
        return values()[(ordinal() + 3) % 4];
    }

    /** The side to the right when facing this way. */
    public Side right() {
        return values()[(ordinal() + 1) % 4];
    }

    /** The side for a one-block horizontal step, or null if the step isn't along one axis. */
    public static Side ofStep(int dx, int dz) {
        for (Side s : values()) {
            if (s.dx == Integer.signum(dx) && s.dz == Integer.signum(dz) && (dx == 0 || dz == 0) && (dx != 0 || dz != 0)) {
                return s;
            }
        }
        return null;
    }

    /** Dominant compass side of a horizontal vector, or null if it's (nearly) zero. */
    public static Side dominant(double dx, double dz) {
        if (Math.abs(dx) < 1e-4 && Math.abs(dz) < 1e-4) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? EAST : WEST;
        }
        return dz > 0 ? SOUTH : NORTH;
    }

    public static Side parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
