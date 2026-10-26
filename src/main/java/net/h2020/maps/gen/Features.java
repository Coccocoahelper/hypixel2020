package net.h2020.maps.gen;

import java.util.Random;

/**
 * Landmark structures placed on the raised centre of mid islands. Each builder starts on the walking level y0
 * (the first air block above the platform) and returns the radius it occupies, so callers can keep the mid
 * point and generators clear of it.
 */
public final class Features {
    private Features() {}

    /** Large Bed Wars centrepiece. */
    public static int bedwarsCentre(BlockCanvas c, Theme t, Random r, int cx, int y0, int cz) {
        switch (t.midFeature) {
            case LIGHTHOUSE: return lighthouse(c, t, cx, y0, cz, 15);
            case DOME: return aquariumDome(c, t, r, cx, y0, cz, 6);
            case GREAT_TREE: Decor.giantJungleTree(c, r, cx, y0 - 1, cz, 15); return 2;
            case FORTRESS: return fortress(c, t, cx, y0, cz);
            default: return skywarsCentre(c, t, r, cx, y0, cz);
        }
    }

    /** Small SkyWars centrepiece (fits inside a radius ~4 platform with chests at (+-2,+-2)). */
    public static int skywarsCentre(BlockCanvas c, Theme t, Random r, int cx, int y0, int cz) {
        switch (t.midFeature) {
            case WINDMILL: return windmill(c, t, cx, y0, cz);
            case OBELISK: return obelisk(c, t, cx, y0, cz);
            case ICE_SPIRE: return iceSpire(c, r, cx, y0, cz);
            case LIGHTHOUSE: return lighthouse(c, t, cx, y0, cz, 9);
            default: return shrine(c, t, cx, y0, cz);
        }
    }

    /** Striped lighthouse with gallery, lantern room and cap. */
    public static int lighthouse(BlockCanvas c, Theme t, int cx, int y0, int cz, int height) {
        double rad = 2.6;
        for (int y = 0; y < height; y++) {
            int band = (y / 3) % 2 == 0 ? B.s(B.WOOL, 14) : B.s(B.WOOL, 0);
            Shapes.cylinder(c, cx, cz, rad - (y > height - 4 ? 0.4 : 0), y0 + y, y0 + y, band);
        }
        // door + windows for detail
        c.set(cx, y0, cz + 2, B.s(B.SPRUCE_STAIRS, Dir.NORTH.stairs()));
        c.set(cx, y0 + 1, cz + 2, B.s(B.TRAPDOOR, 4 | Decor.trapFacing(Dir.SOUTH)));
        for (int y = 4; y < height - 2; y += 4) c.set(cx + (y % 8 == 0 ? 2 : -2), y0 + y, cz, B.s(B.GLASS_PANE));
        int gy = y0 + height;
        Shapes.disc(c, cx, cz, 3.7, 2, gy, gy, B.s(B.STONE_BRICK));
        Shapes.ring(c, cx, cz, 3.4, gy + 1, B.s(B.IRON_BARS));
        // lantern room
        Shapes.tube(c, cx, cz, 2.2, gy + 1, gy + 3, B.s(B.GLASS));
        c.fill(cx, gy + 1, cz, cx, gy + 3, cz, B.s(B.GLOWSTONE));
        c.set(cx + 1, gy + 2, cz, B.s(B.SEA_LANTERN)); c.set(cx - 1, gy + 2, cz, B.s(B.SEA_LANTERN));
        c.set(cx, gy + 2, cz + 1, B.s(B.SEA_LANTERN)); c.set(cx, gy + 2, cz - 1, B.s(B.SEA_LANTERN));
        Shapes.disc(c, cx, cz, 2.6, 2, gy + 4, gy + 4, B.s(B.STAINED_CLAY, 14));
        Shapes.disc(c, cx, cz, 1.5, 2, gy + 5, gy + 5, B.s(B.STAINED_CLAY, 14));
        c.set(cx, gy + 6, cz, B.s(B.WOOL, 14));
        c.set(cx, gy + 7, cz, B.s(B.FENCE));
        c.set(cx, gy + 8, cz, B.s(B.FENCE));
        return 3;
    }

    /** Glass dome filled with water: sea-lantern pillar, coral and sand floor inside, quartz ring outside. */
    public static int aquariumDome(BlockCanvas c, Theme t, Random r, int cx, int y0, int cz, int rad) {
        // floor: sand inside, quartz ring around the base
        for (int x = -rad - 1; x <= rad + 1; x++)
            for (int z = -rad - 1; z <= rad + 1; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= rad - 1) c.set(cx + x, y0 - 1, cz + z, B.s(B.SAND));
                else if (d <= rad + 1) c.set(cx + x, y0 - 1, cz + z, t.floorTrim);
            }
        Shapes.dome(c, cx, y0, cz, rad, t.glass, B.s(B.WATER));
        // the dome's lowest shell ring sits on y0; inside
        for (int y = 0; y <= rad - 2; y++) c.set(cx, y0 + y, cz, y % 2 == 0 ? B.s(B.SEA_LANTERN) : B.s(B.PRISMARINE, 1));
        int[][] spots = {{2, 1}, {-2, 2}, {1, -3}, {-3, -1}, {3, 3}};
        for (int[] s : spots) {
            int h = 1 + r.nextInt(3);
            int col = B.s(B.STAINED_CLAY, new int[]{6, 1, 4, 10}[r.nextInt(4)]);
            for (int y = 0; y < h; y++) c.set(cx + s[0], y0 + y, cz + s[1], col);
            if (r.nextBoolean()) c.set(cx + s[0], y0 + h, cz + s[1], B.s(B.SPONGE, 1));
        }
        return rad;
    }

    /** Nether brick fortress keep: 4 corner towers joined by arches, a central brazier with eternal fire. */
    public static int fortress(BlockCanvas c, Theme t, int cx, int y0, int cz) {
        int nb = B.s(B.NETHER_BRICK);
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                int x1 = sx > 0 ? cx + 3 : cx - 4, z1 = sz > 0 ? cz + 3 : cz - 4;
                c.fill(x1, y0, z1, x1 + 1, y0 + 7, z1 + 1, nb);
                c.set(x1, y0 + 8, z1, nb); c.set(x1 + 1, y0 + 8, z1 + 1, nb);
                c.set(x1 + 1, y0 + 8, z1, B.s(B.NETHER_FENCE)); c.set(x1, y0 + 8, z1 + 1, B.s(B.NETHER_FENCE));
                c.set(x1, y0 + 9, z1, B.s(B.GLOWSTONE));
            }
        // arches between the towers (walkable underneath: clearance 6)
        for (int i = -3; i <= 3; i++) {
            for (int side = -1; side <= 1; side += 2) {
                c.set(cx + i, y0 + 7, cz + side * 4, nb);
                c.set(cx + side * 4, y0 + 7, cz + i, nb);
                if (Math.abs(i) <= 2) {
                    c.set(cx + i, y0 + 6, cz + side * 4, B.s(B.NETHER_BRICK_STAIRS, (side > 0 ? Dir.SOUTH : Dir.NORTH).stairs() | 4));
                    c.set(cx + side * 4, y0 + 6, cz + i, B.s(B.NETHER_BRICK_STAIRS, (side > 0 ? Dir.EAST : Dir.WEST).stairs() | 4));
                }
                if (Math.abs(i) <= 3 && (i & 1) == 0) {
                    c.set(cx + i, y0 + 8, cz + side * 4, B.s(B.NETHER_FENCE));
                    c.set(cx + side * 4, y0 + 8, cz + i, B.s(B.NETHER_FENCE));
                }
            }
        }
        // brazier
        c.fill(cx - 1, y0, cz - 1, cx + 1, y0, cz + 1, nb);
        c.fill(cx - 1, y0 + 1, cz - 1, cx + 1, y0 + 1, cz + 1, B.s(B.NETHER_BRICK_STAIRS, 0));
        for (Dir d : Dir.values()) c.set(cx + d.dx, y0 + 1, cz + d.dz, B.s(B.NETHER_BRICK_STAIRS, d.opposite().stairs()));
        c.set(cx - 1, y0 + 1, cz - 1, nb); c.set(cx + 1, y0 + 1, cz - 1, nb);
        c.set(cx - 1, y0 + 1, cz + 1, nb); c.set(cx + 1, y0 + 1, cz + 1, nb);
        c.set(cx, y0 + 1, cz, B.s(B.NETHERRACK));
        c.set(cx, y0 + 2, cz, B.s(B.FIRE));
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) c.set(cx + sx, y0 + 2, cz + sz, B.s(B.NETHER_FENCE));
        return 2;
    }

    /** Wooden windmill: stone base, plank tower, wool sails on the north face. */
    public static int windmill(BlockCanvas c, Theme t, int cx, int y0, int cz) {
        c.fill(cx - 1, y0, cz - 1, cx + 1, y0 + 1, cz + 1, B.s(B.COBBLE));
        c.fill(cx - 1, y0 + 2, cz - 1, cx + 1, y0 + 6, cz + 1, t.wall);
        for (int y = y0; y <= y0 + 6; y++) {
            c.set(cx - 1, y, cz - 1, t.pillar); c.set(cx + 1, y, cz - 1, t.pillar);
            c.set(cx - 1, y, cz + 1, t.pillar); c.set(cx + 1, y, cz + 1, t.pillar);
        }
        c.set(cx, y0 + 3, cz + 1, B.s(B.GLASS_PANE));
        c.set(cx, y0 + 7, cz, t.roof);
        for (Dir d : Dir.values()) {
            c.set(cx + d.dx, y0 + 7, cz + d.dz, t.roofStairs(d.opposite()));
        }
        c.set(cx - 1, y0 + 7, cz - 1, t.roofSlab); c.set(cx + 1, y0 + 7, cz - 1, t.roofSlab);
        c.set(cx - 1, y0 + 7, cz + 1, t.roofSlab); c.set(cx + 1, y0 + 7, cz + 1, t.roofSlab);
        // hub + sails (in the x/y plane, one block in front of the north wall)
        int hz = cz - 2, hy = y0 + 5;
        c.set(cx, hy, hz, B.s(B.LOG, B.OAK | 8));
        for (int k = 1; k <= 4; k++) {
            c.set(cx, hy + k, hz, t.fence); c.set(cx, hy - k, hz, t.fence);
            c.set(cx + k, hy, hz, t.fence); c.set(cx - k, hy, hz, t.fence);
            if (k >= 2) {
                c.set(cx + 1, hy + k, hz, B.s(B.WOOL, 0));
                c.set(cx - 1, hy - k, hz, B.s(B.WOOL, 0));
                c.set(cx + k, hy - 1, hz, B.s(B.WOOL, 0));
                c.set(cx - k, hy + 1, hz, B.s(B.WOOL, 0));
            }
        }
        return 2;
    }

    /** Sandstone obelisk with a glowing tip on a stepped base. */
    public static int obelisk(BlockCanvas c, Theme t, int cx, int y0, int cz) {
        c.fill(cx - 1, y0, cz - 1, cx + 1, y0, cz + 1, B.s(B.SANDSTONE, 1));
        for (Dir d : Dir.values()) c.set(cx + d.dx, y0 + 1, cz + d.dz, B.s(B.SANDSTONE_STAIRS, d.opposite().stairs()));
        for (int y = 1; y <= 7; y++) c.set(cx, y0 + y, cz, y % 3 == 0 ? B.s(B.SANDSTONE, 1) : B.s(B.SANDSTONE, 2));
        c.set(cx, y0 + 8, cz, B.s(B.GOLD_BLOCK));
        c.set(cx, y0 + 9, cz, B.s(B.GLOWSTONE));
        c.set(cx - 1, y0 + 1, cz - 1, B.s(B.SLAB, B.SLAB_SANDSTONE)); c.set(cx + 1, y0 + 1, cz + 1, B.s(B.SLAB, B.SLAB_SANDSTONE));
        c.set(cx + 1, y0 + 1, cz - 1, B.s(B.SLAB, B.SLAB_SANDSTONE)); c.set(cx - 1, y0 + 1, cz + 1, B.s(B.SLAB, B.SLAB_SANDSTONE));
        return 1;
    }

    /** Tapering packed-ice spire with ice shards. */
    public static int iceSpire(BlockCanvas c, Random r, int cx, int y0, int cz) {
        int h = 11;
        for (int y = 0; y < h; y++) {
            double rad = 1.6 * (1 - y / (double) h) + 0.4;
            Shapes.disc(c, cx, cz, rad, 2, y0 + y, y0 + y, y % 4 == 3 ? B.s(B.ICE) : B.s(B.PACKED_ICE));
        }
        c.set(cx, y0 + h, cz, B.s(B.SEA_LANTERN));
        for (Dir d : Dir.values()) {
            int len = 2 + r.nextInt(2);
            for (int k = 0; k < len; k++) c.set(cx + d.dx * (2 + k / 2), y0 + k, cz + d.dz * (2 + k / 2), B.s(B.PACKED_ICE));
        }
        return 2;
    }

    /** Stone-brick shrine: pillar with lamp and four stair seats. */
    public static int shrine(BlockCanvas c, Theme t, int cx, int y0, int cz) {
        c.set(cx, y0, cz, t.floorAlt);
        for (int y = 1; y <= 4; y++) c.set(cx, y0 + y, cz, t.pillar);
        c.set(cx, y0 + 5, cz, t.lamp);
        c.set(cx, y0 + 6, cz, t.slab);
        for (Dir d : Dir.values()) {
            c.set(cx + d.dx, y0, cz + d.dz, t.stairs(d.opposite()));
            c.set(cx + d.dx, y0 + 4, cz + d.dz, t.stairsTop(d.opposite()));
            c.set(cx + d.dx, y0 + 5, cz + d.dz, t.fence);
        }
        return 1;
    }
}
