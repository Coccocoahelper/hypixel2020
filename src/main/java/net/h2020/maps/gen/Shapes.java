package net.h2020.maps.gen;

import java.util.Random;

/** Terrain primitives: floating islands (layered, tapering underside), discs, cylinders, domes, ramps. */
public final class Shapes {
    private Shapes() {}

    /**
     * Paint a floating island. The top surface is flat at {@code top} (edges occasionally dip by one), the
     * underside tapers into a rough inverted cone like Hypixel islands.
     *
     * @param squareness 0 = ellipse, 1 = nearly square (super-ellipse exponent 2..8)
     * @param ragged     edge noise amplitude relative to the radius
     * @param ores       sprinkle ores into the stone core (SkyWars)
     */
    public static void island(BlockCanvas c, Theme t, Noise n, double cx, int top, double cz, double rx, double rz,
                              double depth, double squareness, double ragged, boolean ores) {
        double p = 2 + squareness * 6;
        int ex = (int) Math.ceil(rx * (1 + ragged)) + 2, ez = (int) Math.ceil(rz * (1 + ragged)) + 2;
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        for (int x = bx - ex; x <= bx + ex; x++) {
            for (int z = bz - ez; z <= bz + ez; z++) {
                double dx = Math.abs(x + 0.5 - cx) / rx, dz = Math.abs(z + 0.5 - cz) / rz;
                double d = Math.pow(Math.pow(dx, p) + Math.pow(dz, p), 1.0 / p);
                double edge = 1 + ragged * n.fractal(x, z, 5, 2);
                if (d > edge) continue;
                double tt = d / edge;
                double taper = Math.pow(Math.max(0, 1 - Math.pow(tt, 1.6)), 0.75);
                double var = 0.8 + 0.4 * (n.value(x * 0.31, 41.7, z * 0.31) * 0.5 + 0.5);
                int colDepth = 1 + (int) Math.round(depth * taper * var);
                int surf = top;
                if (tt > 0.88 && n.rand(x, 3, z) < 0.4) surf = top - 1;
                boolean rimCol = t.rim != 0 && tt > 0.76 + 0.08 * n.value(x * 0.2, 5.5, z * 0.2);
                boolean patch = t.surfacePatches.length > 0 && n.value(x * 0.18, 77.1, z * 0.18) > 0.42;
                for (int i = 0; i < colDepth; i++) {
                    int y = surf - i;
                    int state;
                    if (i == 0) {
                        state = rimCol ? t.rim : patch ? t.surfacePatches[(int) (n.rand(x, 9, z) * t.surfacePatches.length)] : t.top;
                    } else if (i <= t.subDepth) {
                        state = rimCol ? t.rim : t.sub;
                    } else {
                        double rr = n.rand(x, y, z);
                        if (ores && rr < 0.045) state = t.ores[(int) (n.rand(z, y, x) * t.ores.length)];
                        else if (rr < 0.16 && t.coreVariants.length > 0) state = t.coreVariants[(int) (n.rand(y, x, z) * t.coreVariants.length)];
                        else state = t.core;
                    }
                    c.set(x, y, z, state);
                }
            }
        }
    }

    /** Super-ellipse membership test. */
    public static boolean inside(double dx, double dz, double r, double p) {
        return Math.pow(Math.abs(dx) / r, p) + Math.pow(Math.abs(dz) / r, p) <= 1.0;
    }

    /** Filled super-ellipse disc between y1..y2 (inclusive). */
    public static void disc(BlockCanvas c, int cx, int cz, double r, double p, int y1, int y2, int state) {
        int e = (int) Math.ceil(r) + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++)
                if (inside(x, z, r, p))
                    for (int y = y1; y <= y2; y++) c.set(cx + x, y, cz + z, state);
    }

    /** Ring (outline) of a circle on one y. */
    public static void ring(BlockCanvas c, int cx, int cz, double r, int y, int state) {
        int e = (int) Math.ceil(r) + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= r + 0.5 && d > r - 0.5) c.set(cx + x, y, cz + z, state);
            }
    }

    /** Solid vertical cylinder. */
    public static void cylinder(BlockCanvas c, int cx, int cz, double r, int y1, int y2, int state) {
        disc(c, cx, cz, r, 2, y1, y2, state);
    }

    /** Hollow tube of wall thickness 1. */
    public static void tube(BlockCanvas c, int cx, int cz, double r, int y1, int y2, int state) {
        int e = (int) Math.ceil(r) + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= r && d > r - 1.0)
                    for (int y = y1; y <= y2; y++) c.set(cx + x, y, cz + z, state);
            }
    }

    /**
     * Hemisphere shell (glass dome) standing on y0 with optional fill inside (water for aquarium domes). The
     * shell is face-connected (thickness >= 1), so the fluid inside can never leak.
     */
    public static void dome(BlockCanvas c, int cx, int y0, int cz, double r, int shell, int fill) {
        int e = (int) Math.ceil(r) + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++)
                for (int y = 0; y <= e; y++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d <= r && d > r - 1.0) c.set(cx + x, y0 + y, cz + z, shell);
                    else if (d <= r - 1.0 && fill != 0) c.set(cx + x, y0 + y, cz + z, fill);
                }
    }

    /** Blob of leaves (or any block) only into air, a slightly squashed sphere. */
    public static void blob(BlockCanvas c, Random r, int cx, int cy, int cz, double rx, double ry, double rz, int state) {
        int ex = (int) Math.ceil(rx), ey = (int) Math.ceil(ry), ez = (int) Math.ceil(rz);
        for (int x = -ex; x <= ex; x++)
            for (int y = -ey; y <= ey; y++)
                for (int z = -ez; z <= ez; z++) {
                    double d = (x * x) / (rx * rx) + (y * y) / (ry * ry) + (z * z) / (rz * rz);
                    if (d <= 1.0 && !(d > 0.75 && r.nextDouble() < 0.35)) c.setIfAir(cx + x, cy + y, cz + z, state);
                }
    }

    /** Ensure a solid column under (x,z) from y down while there is air, max {@code max} blocks. */
    public static void support(BlockCanvas c, int x, int y, int z, int max, int state) {
        for (int i = 0; i < max; i++) {
            if (!c.isAir(x, y - i, z)) return;
            c.set(x, y - i, z, state);
        }
    }
}
