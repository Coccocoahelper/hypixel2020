package net.h2020.maps.gen;

import org.bukkit.block.Biome;

import java.util.Random;

/**
 * Block palette + decoration style of a generated map. All block values are states (B.s(id,data)).
 * Every generator reads only from the theme, so one layout produces visually distinct maps per theme.
 */
public final class Theme {
    public enum TreeStyle { OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, DARK_OAK, CORAL, NETHER, CACTUS, NONE }
    public enum MidFeature { LIGHTHOUSE, DOME, GREAT_TREE, FORTRESS, WINDMILL, OBELISK, ICE_SPIRE, SHRINE }

    public final String id;
    public Biome biome = Biome.PLAINS;

    // terrain layers
    public int top = B.s(B.GRASS), sub = B.s(B.DIRT), core = B.s(B.STONE);
    public int[] coreVariants = {B.s(B.STONE, 5), B.s(B.COBBLE), B.s(B.GRAVEL)};
    public int rim = 0;                 // optional block replacing top near island edges (sand beaches, etc.)
    public int[] surfacePatches = {};  // occasional top replacements (coarse dirt, gravel, podzol...)
    public int subDepth = 2;

    // built elements
    public int floor = B.s(B.STONE_BRICK), floorAlt = B.s(B.STONE_BRICK, 3), floorTrim = B.s(B.STONE, 6);
    public int path = B.s(B.GRAVEL), pathAlt = B.s(B.COBBLE);
    public int wall = B.s(B.PLANKS, B.OAK), wallAlt = B.s(B.COBBLE), pillar = B.s(B.LOG, B.OAK);
    public int roof = B.s(B.PLANKS, B.SPRUCE);
    public int slab = B.s(B.SLAB, B.SLAB_STONEBRICK);          // bottom slab
    public int roofSlab = B.s(B.WOOD_SLAB, B.SPRUCE);
    public int stairs = B.STONE_BRICK_STAIRS;                  // id only, data from Dir
    public int roofStairs = B.SPRUCE_STAIRS;
    public int fence = B.s(B.FENCE);
    public int wallPost = B.s(B.COBBLE_WALL);
    public int lamp = B.s(B.GLOWSTONE);
    public int glass = B.s(B.GLASS);
    public int accent = B.s(B.WOOL, 0), accentAlt = B.s(B.STAINED_CLAY, 8);
    public int liquid = B.s(B.WATER);                          // 0 = no liquid features
    public int liquidBed = B.s(B.SAND);
    public int crate = B.s(B.LOG, B.SPRUCE | 4);

    // vegetation
    public TreeStyle tree = TreeStyle.OAK;
    public int log = B.s(B.LOG, B.OAK), leaves = B.s(B.LEAVES, B.OAK | 4);
    public int[] flora = {B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.RED_FLOWER, 0), B.s(B.YELLOW_FLOWER)};
    public double floraDensity = 0.22;
    public double treeDensity = 1.0;
    public boolean snowCover = false;
    public boolean vines = false;

    // skywars ores scattered in island cores
    public int[] ores = {B.s(B.COAL_ORE), B.s(B.IRON_ORE), B.s(B.IRON_ORE), B.s(B.GOLD_ORE), B.s(B.DIAMOND_ORE)};

    public MidFeature midFeature = MidFeature.SHRINE;

    public Theme(String id) { this.id = id; }

    public int pick(Random r, int[] arr) { return arr[r.nextInt(arr.length)]; }

    public int stairs(Dir up) { return B.s(stairs, up.stairs()); }
    public int stairsTop(Dir up) { return B.s(stairs, up.stairs() | 4); }
    public int roofStairs(Dir up) { return B.s(roofStairs, up.stairs()); }
    public int slabTop() { return B.withData(slab, B.data(slab) | 8); }
}
