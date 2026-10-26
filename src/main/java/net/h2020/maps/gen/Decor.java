package net.h2020.maps.gen;

import java.util.Random;

/**
 * Decoration library shared by every generator: trees per theme style, lamp posts, banner poles, flora, bushes,
 * rocks, crates, benches, fountains and markets stalls. All methods take the ground block y (the block the thing
 * stands on) and build upwards.
 */
public final class Decor {
    private Decor() {}

    // ---------------------------------------------------------------------------------------------- trees
    public static void tree(BlockCanvas c, Theme t, Random r, int x, int gy, int z) {
        switch (t.tree) {
            case OAK: roundTree(c, r, x, gy, z, B.s(B.LOG, B.OAK), B.s(B.LEAVES, B.OAK | 4), 4 + r.nextInt(2), t.vines); break;
            case BIRCH: roundTree(c, r, x, gy, z, B.s(B.LOG, B.BIRCH), B.s(B.LEAVES, B.BIRCH | 4), 5 + r.nextInt(2), false); break;
            case SPRUCE: spruce(c, r, x, gy, z, 6 + r.nextInt(3), t.snowCover); break;
            case JUNGLE: jungleTree(c, r, x, gy, z, 6 + r.nextInt(3)); break;
            case ACACIA: acacia(c, r, x, gy, z); break;
            case DARK_OAK: darkOak(c, r, x, gy, z); break;
            case CORAL: coral(c, r, x, gy, z); break;
            case NETHER: netherFungus(c, r, x, gy, z); break;
            case CACTUS: cactus(c, r, x, gy, z); break;
            default: break;
        }
    }

    public static void roundTree(BlockCanvas c, Random r, int x, int gy, int z, int log, int leaves, int h, boolean vines) {
        int topY = gy + h;
        for (int y = gy - 1; y <= gy; y++) if (c.isAir(x, y, z)) c.set(x, y, z, B.s(B.DIRT));
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, log);
        for (int dy = -2; dy <= 1; dy++) {
            int rad = dy >= 0 ? 1 : 2;
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad;
                    if (corner && (dy == 1 || r.nextInt(3) == 0)) continue;
                    c.setIfAir(x + dx, topY + dy, z + dz, leaves);
                }
        }
        c.setIfAir(x, topY + 2, z, leaves);
        if (vines) hangVines(c, r, x, topY - 2, z, 3, 3);
    }

    public static void spruce(BlockCanvas c, Random r, int x, int gy, int z, int h, boolean snow) {
        int log = B.s(B.LOG, B.SPRUCE), leaves = B.s(B.LEAVES, B.SPRUCE | 4);
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, log);
        int rad = 0;
        for (int y = h + 1; y >= 2; y--) {
            int ly = gy + y;
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > rad + (rad > 1 ? 1 : 0)) continue;
                    c.setIfAir(x + dx, ly, z + dz, leaves);
                    if (snow && c.isAir(x + dx, ly + 1, z + dz) && r.nextInt(3) == 0) c.set(x + dx, ly + 1, z + dz, B.s(B.SNOW_LAYER));
                }
            rad = rad >= 2 ? 1 : rad + 1;
            if (y < h - 3 && rad == 1 && r.nextBoolean()) rad = 2;
        }
        c.setIfAir(x, gy + h + 2, z, leaves);
    }

    public static void jungleTree(BlockCanvas c, Random r, int x, int gy, int z, int h) {
        int log = B.s(B.LOG, B.JUNGLE), leaves = B.s(B.LEAVES, B.JUNGLE | 4);
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, log);
        Shapes.blob(c, r, x, gy + h, z, 2.8, 1.6, 2.8, leaves);
        Shapes.blob(c, r, x + 1, gy + h - 3, z - 1, 1.6, 1.0, 1.6, leaves);
        hangVines(c, r, x, gy + h - 1, z, 3, 4);
        // vines on the trunk
        for (int y = 2; y < h; y++) {
            if (r.nextInt(3) == 0) c.setIfAir(x + 1, gy + y, z, B.s(B.VINE, Dir.WEST.vine()));
            if (r.nextInt(3) == 0) c.setIfAir(x, gy + y, z - 1, B.s(B.VINE, Dir.SOUTH.vine()));
        }
    }

    /** 2x2 trunk giant jungle tree with buttress roots and a wide canopy (used for jungle mid islands). */
    public static void giantJungleTree(BlockCanvas c, Random r, int x, int gy, int z, int h) {
        int log = B.s(B.LOG, B.JUNGLE), leaves = B.s(B.LEAVES, B.JUNGLE | 4);
        for (int y = 1; y <= h; y++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) c.set(x + dx, gy + y, z + dz, log);
        // buttress roots
        Dir[] dirs = Dir.values();
        for (Dir d : dirs) {
            int bx = x + (d.dx > 0 ? 2 : d.dx < 0 ? -1 : r.nextInt(2));
            int bz = z + (d.dz > 0 ? 2 : d.dz < 0 ? -1 : r.nextInt(2));
            for (int y = 1; y <= 2 + r.nextInt(2); y++) c.set(bx, gy + y, bz, log);
            c.set(bx + d.dx, gy + 1, bz + d.dz, B.s(B.LOG, B.JUNGLE | d.logAxis()));
        }
        // branches + canopy layers
        for (int i = 0; i < 4; i++) {
            Dir d = dirs[i];
            int len = 3 + r.nextInt(2);
            int by = gy + h - 3 - r.nextInt(3);
            int bx = x + (d.dx > 0 ? 1 : 0), bz = z + (d.dz > 0 ? 1 : 0);
            for (int k = 1; k <= len; k++) c.set(bx + d.dx * k, by + k / 2, bz + d.dz * k, B.s(B.LOG, B.JUNGLE | d.logAxis()));
            Shapes.blob(c, r, bx + d.dx * (len + 1), by + len / 2 + 1, bz + d.dz * (len + 1), 3.2, 1.8, 3.2, leaves);
            hangVines(c, r, bx + d.dx * (len + 1), by + len / 2, bz + d.dz * (len + 1), 4, 5);
        }
        Shapes.blob(c, r, x, gy + h + 1, z, 5.5, 2.4, 5.5, leaves);
        Shapes.blob(c, r, x + 1, gy + h + 3, z + 1, 3.4, 1.6, 3.4, leaves);
        hangVines(c, r, x, gy + h, z, 6, 7);
    }

    public static void acacia(BlockCanvas c, Random r, int x, int gy, int z) {
        int log = B.s(B.LOG2, 0), leaves = B.s(B.LEAVES2, 4);
        Dir d = Dir.values()[r.nextInt(4)];
        int h = 4 + r.nextInt(2);
        int cx = x, cz = z;
        for (int y = 1; y <= h; y++) {
            if (y > 2 && y % 2 == 1) { cx += d.dx; cz += d.dz; }
            c.set(cx, gy + y, cz, log);
        }
        int ty = gy + h + 1;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                if (Math.abs(dx) + Math.abs(dz) <= 4) c.setIfAir(cx + dx, ty, cz + dz, leaves);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.setIfAir(cx + dx, ty + 1, cz + dz, leaves);
    }

    public static void darkOak(BlockCanvas c, Random r, int x, int gy, int z) {
        int log = B.s(B.LOG2, 1), leaves = B.s(B.LEAVES2, 1 | 4);
        int h = 5 + r.nextInt(2);
        for (int y = 1; y <= h; y++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) c.set(x + dx, gy + y, z + dz, log);
        Shapes.blob(c, r, x, gy + h + 1, z, 3.6, 1.6, 3.6, leaves);
        Shapes.blob(c, r, x + 1, gy + h + 1, z + 1, 3.6, 1.6, 3.6, leaves);
    }

    /** Aquarium "tree": branching coral of coloured clay with sponge and a sea lantern bud. */
    public static void coral(BlockCanvas c, Random r, int x, int gy, int z) {
        int[] colors = {6, 1, 4, 10, 2, 9};
        int col = B.s(B.STAINED_CLAY, colors[r.nextInt(colors.length)]);
        int h = 3 + r.nextInt(3);
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, col);
        for (Dir d : Dir.values()) {
            if (r.nextInt(3) == 0) continue;
            int by = gy + 2 + r.nextInt(Math.max(1, h - 1));
            c.setIfAir(x + d.dx, by, z + d.dz, col);
            c.setIfAir(x + d.dx, by + 1, z + d.dz, col);
            if (r.nextBoolean()) c.setIfAir(x + d.dx * 2, by + 1, z + d.dz * 2, col);
        }
        c.set(x, gy + h + 1, z, r.nextInt(3) == 0 ? B.s(B.SEA_LANTERN) : B.s(B.SPONGE));
    }

    /** Nether "tree": huge mushroom on a nether brick fence stalk. */
    public static void netherFungus(BlockCanvas c, Random r, int x, int gy, int z) {
        boolean red = r.nextBoolean();
        int capId = red ? 100 : 99;
        int h = 4 + r.nextInt(3);
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, B.s(capId, 10));
        int capY = gy + h + 1;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                c.setIfAir(x + dx, capY, z + dz, B.s(capId, 14));
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) c.setIfAir(x + dx, capY - 1, z + dz, B.s(capId, 14));
            }
        c.set(x, capY - 1, z, B.s(B.GLOWSTONE));
    }

    public static void cactus(BlockCanvas c, Random r, int x, int gy, int z) {
        int h = 1 + r.nextInt(3);
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, B.s(B.CACTUS));
    }

    public static void hangVines(BlockCanvas c, Random r, int cx, int y, int cz, int radius, int maxLen) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (r.nextInt(4) != 0) continue;
                int x = cx + dx, z = cz + dz;
                if (!c.isAir(x, y, z)) continue;
                // find an adjacent leaf to hang from
                for (Dir d : Dir.values()) {
                    int sid = c.id(x + d.dx, y, z + d.dz);
                    if (sid == B.LEAVES || sid == B.LEAVES2) {
                        int len = 1 + r.nextInt(maxLen);
                        for (int k = 0; k < len && c.isAir(x, y - k, z); k++) c.set(x, y - k, z, B.s(B.VINE, d.vine()));
                        break;
                    }
                }
            }
    }

    // ---------------------------------------------------------------------------------------------- props
    /** Fence post with a lamp block and a slab cap (Hypixel style street lamp). */
    public static void lampPost(BlockCanvas c, Theme t, int x, int gy, int z, int h) {
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, t.fence);
        c.set(x, gy + h + 1, z, t.lamp);
        c.set(x, gy + h + 2, z, t.slab);
    }

    /** Lantern hanging from a 2-arm post: post, arm over one side, lamp hanging below. */
    public static void hangingLamp(BlockCanvas c, Theme t, int x, int gy, int z, Dir arm, int h) {
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, t.fence);
        c.set(x, gy + h + 1, z, t.slab);
        c.set(x + arm.dx, gy + h + 1, z + arm.dz, t.slab);
        c.set(x + arm.dx, gy + h, z + arm.dz, t.fence);
        c.set(x + arm.dx, gy + h - 1, z + arm.dz, t.lamp);
    }

    /** Fence pole carrying a standing banner (team colour). dyeData = dye colour data (15 - wool). */
    public static void bannerPole(BlockCanvas c, Theme t, int x, int gy, int z, int h, Dir facing, int dyeData) {
        for (int y = 1; y <= h; y++) c.set(x, gy + y, z, t.fence);
        c.standingBanner(x, gy + h + 1, z, facing.rotation16(), dyeData);
    }

    /** Scatter theme flora on exposed top blocks within a box. */
    public static void flora(BlockCanvas c, Theme t, Random r, int x1, int z1, int x2, int z2, int yMin, int yMax, double density) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                int y = c.surface(x, z, yMax);
                if (y < yMin) continue;
                int top = c.get(x, y, z);
                if (!c.isAir(x, y + 1, z)) continue;
                int id = B.id(top);
                boolean soil = id == B.GRASS || id == B.DIRT || id == B.SAND || id == B.SOUL_SAND || id == B.NETHERRACK
                        || id == B.SNOW_BLOCK || id == B.MYCELIUM;
                if (!soil) continue;
                if (t.snowCover && id != B.SAND) {
                    if (r.nextDouble() < 0.8) c.set(x, y + 1, z, B.s(B.SNOW_LAYER, r.nextInt(5) == 0 ? 1 : 0));
                    else if (r.nextDouble() < density * 2) c.set(x, y + 1, z, B.s(B.TALL_GRASS, 2));
                    continue;
                }
                if (r.nextDouble() >= density) continue;
                int f = t.flora[r.nextInt(t.flora.length)];
                int fid = B.id(f);
                if (fid == B.NETHER_WART && id != B.SOUL_SAND) continue;
                if ((fid == B.DEAD_BUSH || fid == B.CACTUS) && id != B.SAND && id != B.HARD_CLAY && id != B.STAINED_CLAY) {
                    if (fid == B.CACTUS) continue;
                }
                if (fid == B.CACTUS) {
                    boolean clear = c.isAir(x + 1, y + 1, z) && c.isAir(x - 1, y + 1, z) && c.isAir(x, y + 1, z + 1) && c.isAir(x, y + 1, z - 1);
                    if (!clear) continue;
                }
                if (fid == B.DOUBLE_PLANT) {
                    if (!c.isAir(x, y + 2, z)) continue;
                    c.set(x, y + 1, z, f);
                    c.set(x, y + 2, z, B.s(B.DOUBLE_PLANT, 8));
                } else c.set(x, y + 1, z, f);
            }
    }

    /** Small leafy bush. */
    public static void bush(BlockCanvas c, Theme t, Random r, int x, int gy, int z) {
        int leaves = t.leaves;
        c.set(x, gy + 1, z, t.log);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!(Math.abs(dx) == 1 && Math.abs(dz) == 1 && r.nextBoolean())) c.setIfAir(x + dx, gy + 1, z + dz, leaves);
        c.setIfAir(x, gy + 2, z, leaves);
    }

    /** Pile of stones. */
    public static void rocks(BlockCanvas c, Random r, int x, int gy, int z, int[] palette) {
        int n = 2 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            int dx = r.nextInt(3) - 1, dz = r.nextInt(3) - 1;
            int y = gy + 1;
            while (!c.isAir(x + dx, y, z + dz) && y < gy + 3) y++;
            c.set(x + dx, y, z + dz, palette[r.nextInt(palette.length)]);
        }
    }

    /** Stack of crates/barrels (logs, hay, planks). */
    public static void crates(BlockCanvas c, Theme t, Random r, int x, int gy, int z) {
        c.set(x, gy + 1, z, t.crate);
        if (r.nextBoolean()) c.set(x + 1, gy + 1, z, B.s(B.HAY));
        if (r.nextInt(3) == 0) c.set(x, gy + 2, z, B.s(B.WORKBENCH));
        else if (r.nextBoolean()) c.set(x, gy + 2, z, t.crate);
        if (r.nextInt(3) == 0) c.set(x, gy + 1, z + 1, B.s(B.WOOD_SLAB, B.SPRUCE));
    }

    /** Two stair "bench" seats facing {@code facing} with fence arm rests. */
    public static void bench(BlockCanvas c, Theme t, int x, int gy, int z, Dir facing) {
        Dir side = facing.cw();
        Dir up = facing.opposite(); // the stair's high side is behind the sitter
        for (int i = 0; i < 2; i++) c.set(x + side.dx * i, gy + 1, z + side.dz * i, B.s(B.OAK_STAIRS, up.stairs()));
        // open trapdoors standing against the bench ends act as arm rests
        c.set(x - side.dx, gy + 1, z - side.dz, B.s(B.TRAPDOOR, 4 | trapFacing(side.opposite())));
        c.set(x + side.dx * 2, gy + 1, z + side.dz * 2, B.s(B.TRAPDOOR, 4 | trapFacing(side)));
    }

    /** Round fountain: basin of {@code rim}, water, centre column with lamp and spout. */
    public static void fountain(BlockCanvas c, Theme t, int cx, int gy, int cz, int r, int rim, int column) {
        int e = r + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > r + 0.5) continue;
                c.set(cx + x, gy - 1, cz + z, t.floorTrim);
                if (d > r - 0.5) {
                    c.set(cx + x, gy, cz + z, rim);
                    c.set(cx + x, gy + 1, cz + z, B.withData(t.slab, B.data(t.slab)));
                } else {
                    c.set(cx + x, gy, cz + z, B.s(B.WATER));
                }
            }
        for (int y = 0; y <= 3; y++) c.set(cx, gy + y, cz, column);
        c.set(cx, gy + 4, cz, t.lamp);
        c.set(cx, gy + 5, cz, t.slabTop());
        for (Dir d : Dir.values()) {
            c.set(cx + d.dx, gy + 3, cz + d.dz, t.stairsTop(d.opposite()));
        }
    }

    /** A little market stall: posts, coloured wool awning, counter. Front faces {@code front}. */
    public static void stall(BlockCanvas c, Theme t, Random r, int x, int gy, int z, Dir front, int woolData) {
        Dir side = front.cw();
        for (int i = -1; i <= 1; i += 2) {
            for (int y = 1; y <= 3; y++) {
                c.set(x + side.dx * i * 2, gy + y, z + side.dz * i * 2, t.fence);
                c.set(x + side.dx * i * 2 - front.dx * 2, gy + y, z + side.dz * i * 2 - front.dz * 2, t.fence);
            }
        }
        for (int s = -2; s <= 2; s++)
            for (int f = -2; f <= 0; f++) {
                int wx = x + side.dx * s + front.dx * f, wz = z + side.dz * s + front.dz * f;
                c.set(wx, gy + 4, wz, B.s(B.WOOL, (s & 1) == 0 ? woolData : 0));
            }
        for (int s = -1; s <= 1; s++) {
            c.set(x + side.dx * s, gy + 1, z + side.dz * s, t.crate);
            c.set(x + side.dx * s, gy + 2, z + side.dz * s, B.s(B.WOOD_SLAB, B.OAK));
        }
        c.set(x - front.dx, gy + 1, z - front.dz, B.s(B.CHEST, front.facing()));
    }

    // ---------------------------------------------------------------------------------------------- helpers
    public static int woolToDye(int woolData) { return 15 - woolData; }

    /** 1.8 trapdoor facing bits. */
    public static int trapFacing(Dir d) {
        switch (d) {
            case NORTH: return 0;
            case SOUTH: return 1;
            case WEST: return 2;
            default: return 3;
        }
    }
}
