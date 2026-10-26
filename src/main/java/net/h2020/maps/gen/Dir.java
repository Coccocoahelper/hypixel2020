package net.h2020.maps.gen;

/**
 * Horizontal direction with the 1.8 block-data encodings that depend on facing.
 * Ordinal order matches bed/pumpkin data and yaw/90: SOUTH(+z), WEST(-x), NORTH(-z), EAST(+x).
 */
public enum Dir {
    SOUTH(0, 1), WEST(-1, 0), NORTH(0, -1), EAST(1, 0);

    public final int dx, dz;

    Dir(int dx, int dz) { this.dx = dx; this.dz = dz; }

    /** Turn right (clockwise seen from above). */
    public Dir cw() { return values()[(ordinal() + 1) & 3]; }
    public Dir ccw() { return values()[(ordinal() + 3) & 3]; }
    public Dir opposite() { return values()[(ordinal() + 2) & 3]; }

    /** Minecraft yaw a player looking this way has. */
    public float yaw() { return ordinal() * 90f; }

    /** Bed data: direction from foot to head. */
    public int bed() { return ordinal(); }
    /** Pumpkin / jack-o-lantern / fence gate data: the direction the face looks. */
    public int pumpkin() { return ordinal(); }
    /** Chest, furnace, ladder, wall sign, wall banner: data for "front faces this way". */
    public int facing() {
        switch (this) {
            case NORTH: return 2;
            case SOUTH: return 3;
            case WEST: return 4;
            default: return 5;
        }
    }
    /** Stairs: data for "you walk up the stair while moving this way". */
    public int stairs() {
        switch (this) {
            case EAST: return 0;
            case WEST: return 1;
            case SOUTH: return 2;
            default: return 3;
        }
    }
    /** Wall torch pointing this way (attached to the block on the opposite side). */
    public int torch() {
        switch (this) {
            case EAST: return 1;
            case WEST: return 2;
            case SOUTH: return 3;
            default: return 4;
        }
    }
    /** Log axis data for a log lying along this direction. */
    public int logAxis() { return dx != 0 ? 4 : 8; }
    /** Vine data for a vine attached to the block on this side of it. */
    public int vine() {
        switch (this) {
            case SOUTH: return 1;
            case WEST: return 2;
            case NORTH: return 4;
            default: return 8;
        }
    }
    /** Standing banner / sign rotation (0..15) looking this way. */
    public int rotation16() { return ordinal() * 4; }

    /** Closest cardinal for a delta. */
    public static Dir toward(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? EAST : WEST;
        return dz > 0 ? SOUTH : NORTH;
    }

    /** Minecraft yaw for looking from (fx,fz) to (tx,tz). */
    public static float yawTowards(double fx, double fz, double tx, double tz) {
        double dx = tx - fx, dz = tz - fz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (yaw < 0) yaw += 360f;
        return Math.round(yaw * 10f) / 10f;
    }
}
