package net.h2020.maps.gen;

/**
 * A rotated local coordinate frame used to author a structure once and stamp it in any of the four cardinal
 * orientations. Local axes: s = lateral (positive = to the right of forward), y = up, f = forward.
 */
public final class Frame {
    public final int ox, oy, oz;
    public final Dir forward;
    public final Dir right;

    public Frame(int ox, int oy, int oz, Dir forward) {
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.forward = forward;
        this.right = forward.cw();
    }

    public int x(int s, int f) { return ox + s * right.dx + f * forward.dx; }
    public int z(int s, int f) { return oz + s * right.dz + f * forward.dz; }
    public int y(int y) { return oy + y; }

    public double xd(double s, double f) { return ox + s * right.dx + f * forward.dx; }
    public double zd(double s, double f) { return oz + s * right.dz + f * forward.dz; }

    /** World direction for a local direction expressed relative to forward (FORWARD=SOUTH in local terms). */
    public Dir dir(Dir local) {
        // local SOUTH == forward; rotate by forward's ordinal
        return Dir.values()[(local.ordinal() + forward.ordinal()) & 3];
    }

    public Dir fwd() { return forward; }
    public Dir back() { return forward.opposite(); }
    public Dir rightDir() { return right; }
    public Dir leftDir() { return right.opposite(); }

    // --- placement helpers -----------------------------------------------------------------------------------
    public void set(BlockCanvas c, int s, int y, int f, int state) { c.set(x(s, f), oy + y, z(s, f), state); }
    public void setIfAir(BlockCanvas c, int s, int y, int f, int state) { c.setIfAir(x(s, f), oy + y, z(s, f), state); }
    public int get(BlockCanvas c, int s, int y, int f) { return c.get(x(s, f), oy + y, z(s, f)); }

    public void fill(BlockCanvas c, int s1, int y1, int f1, int s2, int y2, int f2, int state) {
        for (int s = Math.min(s1, s2); s <= Math.max(s1, s2); s++)
            for (int f = Math.min(f1, f2); f <= Math.max(f1, f2); f++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    set(c, s, y, f, state);
    }
}
