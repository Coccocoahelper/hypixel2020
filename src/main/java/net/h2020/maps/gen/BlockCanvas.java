package net.h2020.maps.gen;

import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Chunk;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.Item;
import net.minecraft.server.v1_8_R3.TileEntity;
import net.minecraft.server.v1_8_R3.TileEntityBanner;
import net.minecraft.server.v1_8_R3.TileEntityFlowerPot;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Sparse in-memory block buffer. Generators paint into it freely (read-back is supported for decoration
 * passes), then {@link #flush} writes everything straight into NMS chunk sections, creates tile entities and
 * performs a full vanilla relight of every touched chunk, so the saved template has correct sky/block light.
 */
public final class BlockCanvas {
    private final HashMap<Long, Integer> blocks = new HashMap<Long, Integer>(1 << 16);
    private final Map<Long, int[]> pots = new HashMap<Long, int[]>();
    private final Map<Long, Integer> banners = new HashMap<Long, Integer>();
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    static int kx(long k) { int v = (int) (k >>> 38); return v >= (1 << 25) ? v - (1 << 26) : v; }
    static int kz(long k) { int v = (int) ((k >>> 12) & 0x3FFFFFF); return v >= (1 << 25) ? v - (1 << 26) : v; }
    static int ky(long k) { return (int) (k & 0xFFF); }

    // --- painting ----------------------------------------------------------------------------------------------
    public void set(int x, int y, int z, int state) {
        if (y < 0 || y > 255) return;
        long k = key(x, y, z);
        if (state == 0) { blocks.remove(k); pots.remove(k); banners.remove(k); return; }
        blocks.put(k, state);
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
    }

    public void set(int x, int y, int z, int id, int data) { set(x, y, z, B.s(id, data)); }

    public void setIfAir(int x, int y, int z, int state) { if (get(x, y, z) == 0) set(x, y, z, state); }

    /** Replace only if the current block is solid ground (used for surface re-texturing). */
    public void replaceGround(int x, int y, int z, int state) { if (B.isGround(get(x, y, z))) set(x, y, z, state); }

    public int get(int x, int y, int z) {
        if (y < 0 || y > 255) return 0;
        Integer v = blocks.get(key(x, y, z));
        return v == null ? 0 : v;
    }

    public int id(int x, int y, int z) { return get(x, y, z) >>> 4; }
    public boolean isAir(int x, int y, int z) { return get(x, y, z) == 0; }
    public boolean isGround(int x, int y, int z) { return B.isGround(get(x, y, z)); }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, int state) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    set(x, y, z, state);
    }

    /** Highest ground block at or below fromY in this column, or -1. */
    public int surface(int x, int z, int fromY) {
        for (int y = Math.min(255, fromY); y >= 0; y--) if (B.isGround(get(x, y, z))) return y;
        return -1;
    }

    /** Flower pot with a plant (block id/data of the plant, e.g. RED_FLOWER:0). */
    public void pot(int x, int y, int z, int plantId, int plantData) {
        set(x, y, z, B.s(B.FLOWER_POT));
        pots.put(key(x, y, z), new int[]{plantId, plantData});
    }

    /** Standing banner (rotation 0..15) with a base colour in dye-data terms (0 black .. 15 white). */
    public void standingBanner(int x, int y, int z, int rotation, int dyeData) {
        set(x, y, z, B.s(B.STANDING_BANNER, rotation));
        banners.put(key(x, y, z), dyeData);
    }

    /** Wall banner attached facing {@code facing}. */
    public void wallBanner(int x, int y, int z, Dir facing, int dyeData) {
        set(x, y, z, B.s(B.WALL_BANNER, facing.facing()));
        banners.put(key(x, y, z), dyeData);
    }

    public int size() { return blocks.size(); }
    public boolean isEmpty() { return blocks.isEmpty(); }
    public int minX() { return minX; } public int minY() { return minY; } public int minZ() { return minZ; }
    public int maxX() { return maxX; } public int maxY() { return maxY; } public int maxZ() { return maxZ; }

    // --- writing -----------------------------------------------------------------------------------------------
    /**
     * Write every buffered block into the world (main thread). Chunks are loaded/generated as needed, a 2-chunk
     * margin is loaded so vanilla relighting can propagate light across chunk borders.
     */
    public void flush(World world, Logger log) {
        long t0 = System.currentTimeMillis();
        WorldServer ws = ((CraftWorld) world).getHandle();
        Map<Long, Chunk> touched = new HashMap<Long, Chunk>();
        List<BlockPosition> tileEntities = new ArrayList<BlockPosition>();
        List<BlockPosition> emitters = new ArrayList<BlockPosition>();
        Map<Integer, IBlockData> stateCache = new HashMap<Integer, IBlockData>();

        Chunk last = null;
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        for (Map.Entry<Long, Integer> e : blocks.entrySet()) {
            long k = e.getKey();
            int x = kx(k), y = ky(k), z = kz(k);
            int state = e.getValue();
            int cx = x >> 4, cz = z >> 4;
            if (last == null || cx != lastCx || cz != lastCz) {
                long ck = chunkKey(cx, cz);
                last = touched.get(ck);
                if (last == null) {
                    last = loadChunk(ws, cx, cz);
                    touched.put(ck, last);
                }
                lastCx = cx; lastCz = cz;
            }
            IBlockData data = stateCache.get(state);
            if (data == null) {
                Block b = Block.getById(B.id(state));
                data = b.fromLegacyData(B.data(state));
                stateCache.put(state, data);
            }
            ChunkSection[] sections = last.getSections();
            int si = y >> 4;
            ChunkSection sec = sections[si];
            if (sec == null) {
                sec = new ChunkSection(si << 4, true);
                sections[si] = sec;
            }
            sec.setType(x & 15, y & 15, z & 15, data);
            int id = B.id(state);
            if (B.hasTileEntity(id)) tileEntities.add(new BlockPosition(x, y, z));
            if (B.emitsLight(id)) emitters.add(new BlockPosition(x, y, z));
        }

        // margin so the light engine sees loaded neighbours everywhere
        Set<Long> margin = new HashSet<Long>();
        for (Chunk c : touched.values())
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) margin.add(chunkKey(c.locX + dx, c.locZ + dz));
        for (Long ck : margin) loadChunk(ws, (int) (ck >> 32), (int) (long) ck);

        // tile entities (created lazily by the chunk on first access)
        for (BlockPosition p : tileEntities) {
            TileEntity te = ws.getTileEntity(p);
            long k = key(p.getX(), p.getY(), p.getZ());
            if (te instanceof TileEntityFlowerPot) {
                int[] plant = pots.get(k);
                if (plant != null) {
                    Item item = Item.getItemOf(Block.getById(plant[0]));
                    if (item != null) ((TileEntityFlowerPot) te).a(item, plant[1]);
                }
            } else if (te instanceof TileEntityBanner) {
                Integer color = banners.get(k);
                if (color != null) ((TileEntityBanner) te).color = color;
            }
        }

        // lighting: vertical sky light + height maps, then the full vanilla relight per chunk
        for (Chunk c : touched.values()) { c.initLighting(); c.e(); }
        for (Chunk c : touched.values()) c.n();
        for (BlockPosition p : emitters) ws.x(p);
        for (Chunk c : touched.values()) c.e();

        if (log != null)
            log.info("[maps] wrote " + blocks.size() + " blocks into " + touched.size() + " chunks of " + world.getName()
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /**
     * World.getChunkAt returns the shared EmptyChunk for unloaded chunks outside spawn preparation, so always go
     * through the provider's load-or-generate path.
     */
    private static Chunk loadChunk(WorldServer ws, int cx, int cz) {
        return ws.chunkProviderServer.getChunkAt(cx, cz);
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
}
