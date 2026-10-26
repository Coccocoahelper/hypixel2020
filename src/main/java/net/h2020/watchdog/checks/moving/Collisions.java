package net.h2020.watchdog.checks.moving;

import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Blocks;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.Material;
import net.minecraft.server.v1_8_R3.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Block collision helpers mirroring 1.8 World.getCubes / Entity.moveEntity / Entity.checkBlockCollisions.
 * Main thread only. Never loads chunks: an unloaded chunk makes the result lenient.
 */
final class Collisions {
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT = 1.8;

    private Collisions() {}

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    static AxisAlignedBB box(double x, double y, double z) {
        return new AxisAlignedBB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
    }

    private static boolean columnLoaded(World w, int x, int z) {
        return w.getChunkIfLoaded(x >> 4, z >> 4) != null;
    }

    /**
     * Block collision boxes intersecting {@code bb} (World.getCubes without entities and world border).
     * Cells flagged as ghost-air (client has not seen the new solid block yet) are skipped when {@code ghosts} is
     * given. @return false if an unloaded chunk was touched.
     */
    static boolean blockBoxes(World w, AxisAlignedBB bb, Entity e, List<AxisAlignedBB> out, GhostBlocks ghosts, long time) {
        int x0 = floor(bb.a), x1 = floor(bb.d + 1.0), y0 = floor(bb.b - 1.0), y1 = floor(bb.e + 1.0);
        int z0 = floor(bb.c), z1 = floor(bb.f + 1.0);
        boolean loaded = true;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!columnLoaded(w, x, z)) { loaded = false; continue; }
                for (int y = Math.max(0, y0); y < Math.min(256, y1); y++) {
                    BlockPosition pos = new BlockPosition(x, y, z);
                    IBlockData data = w.getType(pos);
                    Block b = data.getBlock();
                    if (b.getMaterial() == Material.AIR) continue;
                    if (ghosts != null && ghosts.isAir(x, y, z, time)) continue;
                    b.a(w, pos, data, bb, out, e);
                }
            }
        }
        return loaded;
    }

    /** Collision boxes of boats/minecarts etc. (Entity.S()) intersecting bb. */
    static boolean entityBoxIntersects(World w, Entity self, AxisAlignedBB bb) {
        List<Entity> list = w.getEntities(self, bb.grow(0.25, 0.25, 0.25));
        for (Entity o : list) {
            AxisAlignedBB c = o.S();
            if (c != null && c.b(bb)) return true;
        }
        return false;
    }

    /**
     * Ground within {@code depth} below the box at (x, y, z). A client that lands less than 0.03 below its last
     * reported position sends no position update (EntityPlayerSP), e.g. the step-up jump that peaks at +1.0156.
     */
    static boolean groundWithin(World w, Entity e, double x, double y, double z, double depth, GhostBlocks ghosts, long time) {
        AxisAlignedBB bb = box(x, y, z);
        AxisAlignedBB probe = new AxisAlignedBB(bb.a, bb.b - depth, bb.c, bb.d, bb.b, bb.f);
        return collides(w, e, probe, null, time, true) || ghosts.solidIntersects(probe, time) || entityBoxIntersects(w, e, probe);
    }

    static boolean collides(World w, Entity e, AxisAlignedBB bb, GhostBlocks ghosts, long time, boolean lenientUnloaded) {
        List<AxisAlignedBB> list = new ArrayList<>(4);
        boolean loaded = blockBoxes(w, bb, e, list, ghosts, time);
        if (!loaded && lenientUnloaded) return true;
        return !list.isEmpty();
    }

    /** Full environment of the player box at (x, y, z). */
    static PosInfo info(World w, Entity e, double x, double y, double z, GhostBlocks ghosts, long time) {
        PosInfo info = new PosInfo();
        info.x = x; info.y = y; info.z = z;
        AxisAlignedBB bb = box(x, y, z);

        // Ground: a downward move of 0.001 would be blocked.
        AxisAlignedBB probe = new AxisAlignedBB(bb.a, bb.b - 0.001, bb.c, bb.d, bb.b, bb.f);
        List<AxisAlignedBB> list = new ArrayList<>(4);
        boolean loaded = blockBoxes(w, probe, e, list, null, time);
        info.loaded = loaded;
        info.ground = !loaded || !list.isEmpty() || ghosts.solidIntersects(probe, time) || entityBoxIntersects(w, e, probe);
        if (info.ground) {
            info.nearGround = true;
        } else {
            AxisAlignedBB deep = new AxisAlignedBB(bb.a, bb.b - 0.5, bb.c, bb.d, bb.b, bb.f);
            list.clear();
            info.nearGround = !blockBoxes(w, deep, e, list, null, time) || !list.isEmpty()
                    || ghosts.solidIntersects(deep, time) || entityBoxIntersects(w, e, deep);
        }

        AxisAlignedBB head = new AxisAlignedBB(bb.a, bb.e, bb.c, bb.d, bb.e + 0.05, bb.f);
        list.clear();
        info.headBlocked = !blockBoxes(w, head, e, list, null, time) || !list.isEmpty();

        // Liquids (Entity.W() handleWaterMovement / EntityLiving lava check).
        if (materialIn(w, bb.grow(0.0, -0.4, 0.0).shrink(0.001, 0.001, 0.001), Material.WATER)) info.liquid = 1;
        else if (materialIn(w, bb.grow(-0.1, -0.4, -0.1), Material.LAVA)) info.liquid = 2;
        info.liquidLoose = info.liquid != 0 || liquidIn(w, bb.grow(0.1, 0.1, 0.1));

        // Ladder / vine at the feet block.
        Block feet = blockAt(w, floor(x), floor(bb.b), floor(z));
        info.climbable = feet == Blocks.LADDER || feet == Blocks.VINE;

        // Entity.checkBlockCollisions: blocks inside the box contracted by 0.001.
        int cx0 = floor(bb.a + 0.001), cx1 = floor(bb.d - 0.001), cy0 = floor(bb.b + 0.001), cy1 = floor(bb.e - 0.001);
        int cz0 = floor(bb.c + 0.001), cz1 = floor(bb.f - 0.001);
        for (int bx = cx0; bx <= cx1; bx++) {
            for (int bz = cz0; bz <= cz1; bz++) {
                for (int by = cy0; by <= cy1; by++) {
                    Block b = blockAt(w, bx, by, bz);
                    if (b == Blocks.WEB) info.web = true;
                    else if (b == Blocks.SOUL_SAND) info.soulSand = true;
                }
            }
        }

        info.onSlime = blockAt(w, floor(x), floor(y - 0.2), floor(z)) == Blocks.SLIME;
        Block below = blockAt(w, floor(x), floor(bb.b) - 1, floor(z));
        info.slip = below == null ? 0.6f : below.frictionFactor;
        return info;
    }

    static Block blockAt(World w, int x, int y, int z) {
        if (y < 0 || y > 255 || !columnLoaded(w, x, z)) return null;
        return w.getType(new BlockPosition(x, y, z)).getBlock();
    }

    /** World.a(AxisAlignedBB, Material): any block of the material in the cells spanned by bb. */
    static boolean materialIn(World w, AxisAlignedBB bb, Material m) {
        int x0 = floor(bb.a), x1 = floor(bb.d + 1.0), y0 = floor(bb.b), y1 = floor(bb.e + 1.0);
        int z0 = floor(bb.c), z1 = floor(bb.f + 1.0);
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!columnLoaded(w, x, z)) continue;
                for (int y = Math.max(0, y0); y < Math.min(256, y1); y++) {
                    if (w.getType(new BlockPosition(x, y, z)).getBlock().getMaterial() == m) return true;
                }
            }
        }
        return false;
    }

    static boolean liquidIn(World w, AxisAlignedBB bb) {
        int x0 = floor(bb.a), x1 = floor(bb.d + 1.0), y0 = floor(bb.b), y1 = floor(bb.e + 1.0);
        int z0 = floor(bb.c), z1 = floor(bb.f + 1.0);
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!columnLoaded(w, x, z)) continue;
                for (int y = Math.max(0, y0); y < Math.min(256, y1); y++) {
                    if (w.getType(new BlockPosition(x, y, z)).getBlock().getMaterial().isLiquid()) return true;
                }
            }
        }
        return false;
    }

    /**
     * Passable: does the move from (x0,y0,z0) to (x1,y1,z1) pass through a collision box? The move is swept in
     * vanilla axis order (Y, then X, then Z) with the box contracted by 0.001; boxes the player already overlapped
     * at the start of a segment are ignored (stuck players may move out). Ghost-air cells are skipped.
     * @return a short description of the hit, or null.
     */
    static String passableHit(World w, Entity e, double x0, double y0, double z0, double x1, double y1, double z1,
                              GhostBlocks ghosts, long time) {
        AxisAlignedBB cur = box(x0, y0, z0).shrink(0.001, 0.001, 0.001);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        if (dy != 0) {
            if (sweepHits(w, e, cur, 0, dy, 0, ghosts, time)) return "y";
            cur = cur.c(0, dy, 0);
        }
        if (dx != 0) {
            if (sweepHits(w, e, cur, dx, 0, 0, ghosts, time)) return "x";
            cur = cur.c(dx, 0, 0);
        }
        if (dz != 0) {
            if (sweepHits(w, e, cur, 0, 0, dz, ghosts, time)) return "z";
        }
        return null;
    }

    private static boolean sweepHits(World w, Entity e, AxisAlignedBB start, double dx, double dy, double dz,
                                     GhostBlocks ghosts, long time) {
        AxisAlignedBB swept = start.a(dx, dy, dz);
        List<AxisAlignedBB> list = new ArrayList<>(4);
        if (!blockBoxes(w, swept, e, list, ghosts, time)) return false;
        for (AxisAlignedBB b : list) {
            if (b.b(swept) && !b.b(start)) return true;
        }
        return false;
    }
}
