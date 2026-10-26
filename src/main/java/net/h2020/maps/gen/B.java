package net.h2020.maps.gen;

/**
 * 1.8 legacy block ids and a tiny "state" encoding (id << 4 | data) used by every generator.
 * Using raw ids keeps the generators compact and lets the canvas write straight into NMS chunk sections.
 */
public final class B {
    private B() {}

    public static int s(int id, int data) { return (id << 4) | (data & 15); }
    public static int s(int id) { return id << 4; }
    public static int id(int state) { return state >>> 4; }
    public static int data(int state) { return state & 15; }
    public static int withData(int state, int data) { return (state & ~15) | (data & 15); }

    public static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, COBBLE = 4, PLANKS = 5, SAPLING = 6, BEDROCK = 7,
            WATER_FLOWING = 8, WATER = 9, LAVA_FLOWING = 10, LAVA = 11, SAND = 12, GRAVEL = 13, GOLD_ORE = 14,
            IRON_ORE = 15, COAL_ORE = 16, LOG = 17, LEAVES = 18, SPONGE = 19, GLASS = 20, LAPIS_ORE = 21,
            LAPIS_BLOCK = 22, SANDSTONE = 24, BED = 26, WEB = 30, TALL_GRASS = 31, DEAD_BUSH = 32, WOOL = 35,
            YELLOW_FLOWER = 37, RED_FLOWER = 38, BROWN_MUSHROOM = 39, RED_MUSHROOM = 40, GOLD_BLOCK = 41,
            IRON_BLOCK = 42, DOUBLE_SLAB = 43, SLAB = 44, BRICK = 45, TNT = 46, BOOKSHELF = 47, MOSSY_COBBLE = 48,
            OBSIDIAN = 49, TORCH = 50, FIRE = 51, OAK_STAIRS = 53, CHEST = 54, DIAMOND_ORE = 56,
            DIAMOND_BLOCK = 57, WORKBENCH = 58, FARMLAND = 60, FURNACE = 61, LADDER = 65, COBBLE_STAIRS = 67,
            LEVER = 69, SNOW_LAYER = 78, ICE = 79, SNOW_BLOCK = 80, CACTUS = 81, CLAY = 82, SUGAR_CANE = 83,
            FENCE = 85, PUMPKIN = 86, NETHERRACK = 87, SOUL_SAND = 88, GLOWSTONE = 89, JACK_O_LANTERN = 91,
            STAINED_GLASS = 95, TRAPDOOR = 96, STONE_BRICK = 98, IRON_BARS = 101, GLASS_PANE = 102, MELON = 103,
            VINE = 106, FENCE_GATE = 107, BRICK_STAIRS = 108, STONE_BRICK_STAIRS = 109, MYCELIUM = 110,
            LILY_PAD = 111, NETHER_BRICK = 112, NETHER_FENCE = 113, NETHER_BRICK_STAIRS = 114, NETHER_WART = 115,
            ENCHANT_TABLE = 116, BREWING_STAND = 117, CAULDRON = 118, END_STONE = 121, WOOD_DOUBLE_SLAB = 125,
            WOOD_SLAB = 126, SANDSTONE_STAIRS = 128, EMERALD_ORE = 129, ENDER_CHEST = 130, SPRUCE_STAIRS = 134,
            BIRCH_STAIRS = 135, JUNGLE_STAIRS = 136, BEACON = 138, COBBLE_WALL = 139, FLOWER_POT = 140,
            ANVIL = 145, QUARTZ_ORE = 153, QUARTZ_BLOCK = 155, QUARTZ_STAIRS = 156, STAINED_CLAY = 159,
            STAINED_PANE = 160, LEAVES2 = 161, LOG2 = 162, ACACIA_STAIRS = 163, DARK_OAK_STAIRS = 164,
            BARRIER = 166, PRISMARINE = 168, SEA_LANTERN = 169, HAY = 170, CARPET = 171, HARD_CLAY = 172,
            COAL_BLOCK = 173, PACKED_ICE = 174, DOUBLE_PLANT = 175, STANDING_BANNER = 176, WALL_BANNER = 177,
            RED_SANDSTONE = 179, RED_SANDSTONE_STAIRS = 180, RED_SANDSTONE_SLAB = 182, SPRUCE_FENCE = 188,
            BIRCH_FENCE = 189, JUNGLE_FENCE = 190, DARK_OAK_FENCE = 191, ACACIA_FENCE = 192, EMERALD_BLOCK = 133,
            REDSTONE_BLOCK = 152, HOPPER = 154, MOB_SPAWNER = 52, SKULL = 144;

    // wood types (planks / log / slab data)
    public static final int OAK = 0, SPRUCE = 1, BIRCH = 2, JUNGLE = 3, ACACIA = 4, DARK_OAK = 5;

    // slab data
    public static final int SLAB_STONE = 0, SLAB_SANDSTONE = 1, SLAB_COBBLE = 3, SLAB_BRICK = 4, SLAB_STONEBRICK = 5,
            SLAB_NETHER = 6, SLAB_QUARTZ = 7, SLAB_TOP = 8;

    /** Blocks that need a tile entity in 1.8 (created after the bulk write). */
    public static boolean hasTileEntity(int id) {
        switch (id) {
            case CHEST: case 146: case ENDER_CHEST: case FURNACE: case 62: case 63: case 68: case SKULL:
            case STANDING_BANNER: case WALL_BANNER: case ENCHANT_TABLE: case BREWING_STAND: case BEACON:
            case FLOWER_POT: case MOB_SPAWNER: case HOPPER: case 23: case 158: case 25: case 84:
                return true;
            default:
                return false;
        }
    }

    /** Blocks a player (or a tree trunk) can stand on / that count as ground for decoration placement. */
    public static boolean isGround(int state) {
        int id = id(state);
        switch (id) {
            case AIR: case WATER: case WATER_FLOWING: case LAVA: case LAVA_FLOWING: case TALL_GRASS: case DEAD_BUSH:
            case YELLOW_FLOWER: case RED_FLOWER: case BROWN_MUSHROOM: case RED_MUSHROOM: case TORCH: case FIRE:
            case SNOW_LAYER: case VINE: case DOUBLE_PLANT: case CARPET: case LADDER: case SAPLING: case LILY_PAD:
            case NETHER_WART: case SUGAR_CANE: case STANDING_BANNER: case WALL_BANNER: case FLOWER_POT:
            case LEAVES: case LEAVES2: case FENCE: case NETHER_FENCE: case SPRUCE_FENCE: case BIRCH_FENCE:
            case JUNGLE_FENCE: case DARK_OAK_FENCE: case ACACIA_FENCE: case COBBLE_WALL: case IRON_BARS:
            case GLASS_PANE: case STAINED_PANE: case WEB: case CACTUS: case BED:
                return false;
            default:
                return true;
        }
    }

    /** Light-emitting blocks (receive an extra light check after the chunk relight). */
    public static boolean emitsLight(int id) {
        switch (id) {
            case GLOWSTONE: case SEA_LANTERN: case JACK_O_LANTERN: case TORCH: case LAVA: case LAVA_FLOWING:
            case FIRE: case BEACON: case 124: case BROWN_MUSHROOM: case BREWING_STAND: case ENDER_CHEST:
                return true;
            default:
                return false;
        }
    }
}
