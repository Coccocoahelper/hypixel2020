package net.h2020.watchdog.framework;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;

/** Immutable axis aligned box snapshot (thread-safe to share). */
public final class Box {
    public final double minX, minY, minZ, maxX, maxY, maxZ;

    public Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
    }

    public static Box of(AxisAlignedBB bb) { return new Box(bb.a, bb.b, bb.c, bb.d, bb.e, bb.f); }

    public static Box block(int x, int y, int z) { return new Box(x, y, z, x + 1, y + 1, z + 1); }

    /** Player-sized box (0.6 x 1.8) with feet at the given position. */
    public static Box player(double x, double y, double z) { return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3); }

    public Box expand(double d) { return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d); }

    public double centerX() { return (minX + maxX) * 0.5; }
    public double centerY() { return (minY + maxY) * 0.5; }
    public double centerZ() { return (minZ + maxZ) * 0.5; }
    public double height() { return maxY - minY; }
}
