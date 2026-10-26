package net.h2020.maps.gen;

import org.bukkit.block.Biome;

/** The built-in palettes. Each call returns a fresh mutable Theme. */
public final class Themes {
    private Themes() {}

    public static Theme byId(String id) {
        if (id == null) return meadow();
        switch (id.toLowerCase()) {
            case "coastal": return coastal();
            case "aquarium": return aquarium();
            case "jungle": return jungle();
            case "nether": return nether();
            case "desert": return desert();
            case "snow": return snow();
            case "shire": return shire();
            default: return meadow();
        }
    }

    /** Seaside village with a striped lighthouse at mid (Lighthouse-like). */
    public static Theme coastal() {
        Theme t = new Theme("coastal");
        t.biome = Biome.BEACH;
        t.top = B.s(B.GRASS); t.sub = B.s(B.DIRT); t.core = B.s(B.STONE);
        t.coreVariants = new int[]{B.s(B.STONE, 5), B.s(B.GRAVEL), B.s(B.COBBLE), B.s(B.STONE, 1)};
        t.rim = B.s(B.SAND);
        t.surfacePatches = new int[]{B.s(B.DIRT, 1)};
        t.floor = B.s(B.SANDSTONE, 2); t.floorAlt = B.s(B.SANDSTONE, 1); t.floorTrim = B.s(B.PLANKS, B.SPRUCE);
        t.path = B.s(B.GRAVEL); t.pathAlt = B.s(B.SAND);
        t.wall = B.s(B.PLANKS, B.BIRCH); t.wallAlt = B.s(B.STONE_BRICK); t.pillar = B.s(B.LOG, B.SPRUCE);
        t.roof = B.s(B.PLANKS, B.SPRUCE); t.roofSlab = B.s(B.WOOD_SLAB, B.SPRUCE);
        t.stairs = B.SANDSTONE_STAIRS; t.roofStairs = B.SPRUCE_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_SANDSTONE);
        t.fence = B.s(B.SPRUCE_FENCE);
        t.lamp = B.s(B.GLOWSTONE);
        t.glass = B.s(B.GLASS);
        t.accent = B.s(B.WOOL, 0); t.accentAlt = B.s(B.STAINED_CLAY, 11);
        t.liquid = B.s(B.WATER); t.liquidBed = B.s(B.SAND);
        t.crate = B.s(B.LOG, B.SPRUCE | 4);
        t.tree = Theme.TreeStyle.OAK;
        t.log = B.s(B.LOG, B.OAK); t.leaves = B.s(B.LEAVES, B.OAK | 4);
        t.flora = new int[]{B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1),
                B.s(B.DOUBLE_PLANT, 2), B.s(B.RED_FLOWER, 0), B.s(B.YELLOW_FLOWER), B.s(B.RED_FLOWER, 8)};
        t.floraDensity = 0.26;
        t.midFeature = Theme.MidFeature.LIGHTHOUSE;
        return t;
    }

    /** Sandy sea floor, prismarine, coral and a water-filled glass dome (Aquarium-like). */
    public static Theme aquarium() {
        Theme t = new Theme("aquarium");
        t.biome = Biome.OCEAN;
        t.top = B.s(B.SAND); t.sub = B.s(B.SANDSTONE); t.core = B.s(B.PRISMARINE);
        t.coreVariants = new int[]{B.s(B.PRISMARINE, 1), B.s(B.PRISMARINE, 2), B.s(B.STONE), B.s(B.SANDSTONE)};
        t.rim = 0;
        t.surfacePatches = new int[]{B.s(B.GRAVEL), B.s(B.CLAY), B.s(B.SAND)};
        t.floor = B.s(B.PRISMARINE, 1); t.floorAlt = B.s(B.PRISMARINE, 2); t.floorTrim = B.s(B.QUARTZ_BLOCK);
        t.path = B.s(B.SANDSTONE, 2); t.pathAlt = B.s(B.PRISMARINE, 1);
        t.wall = B.s(B.QUARTZ_BLOCK); t.wallAlt = B.s(B.PRISMARINE, 1); t.pillar = B.s(B.QUARTZ_BLOCK, 2);
        t.roof = B.s(B.STAINED_CLAY, 9); t.roofSlab = B.s(B.SLAB, B.SLAB_QUARTZ);
        t.stairs = B.QUARTZ_STAIRS; t.roofStairs = B.QUARTZ_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_QUARTZ);
        t.fence = B.s(B.BIRCH_FENCE);
        t.lamp = B.s(B.SEA_LANTERN);
        t.glass = B.s(B.STAINED_GLASS, 3);
        t.accent = B.s(B.WOOL, 3); t.accentAlt = B.s(B.STAINED_CLAY, 9);
        t.liquid = B.s(B.WATER); t.liquidBed = B.s(B.SAND);
        t.crate = B.s(B.SPONGE);
        t.tree = Theme.TreeStyle.CORAL;
        t.log = B.s(B.PRISMARINE, 2); t.leaves = B.s(B.STAINED_CLAY, 6);
        t.flora = new int[]{B.s(B.DEAD_BUSH), B.s(B.TALL_GRASS, 1)};
        t.floraDensity = 0.03;
        t.treeDensity = 1.3;
        t.midFeature = Theme.MidFeature.DOME;
        return t;
    }

    /** Overgrown jungle temple ruins with a giant tree at mid (Amazon-like). */
    public static Theme jungle() {
        Theme t = new Theme("jungle");
        t.biome = Biome.JUNGLE;
        t.top = B.s(B.GRASS); t.sub = B.s(B.DIRT); t.core = B.s(B.STONE);
        t.coreVariants = new int[]{B.s(B.MOSSY_COBBLE), B.s(B.COBBLE), B.s(B.STONE, 5), B.s(B.DIRT)};
        t.surfacePatches = new int[]{B.s(B.DIRT, 1), B.s(B.DIRT, 2)};
        t.floor = B.s(B.STONE_BRICK, 1); t.floorAlt = B.s(B.STONE_BRICK, 3); t.floorTrim = B.s(B.STONE_BRICK, 2);
        t.path = B.s(B.DIRT, 1); t.pathAlt = B.s(B.MOSSY_COBBLE);
        t.wall = B.s(B.PLANKS, B.JUNGLE); t.wallAlt = B.s(B.STONE_BRICK, 1); t.pillar = B.s(B.LOG, B.JUNGLE);
        t.roof = B.s(B.PLANKS, B.JUNGLE); t.roofSlab = B.s(B.WOOD_SLAB, B.JUNGLE);
        t.stairs = B.STONE_BRICK_STAIRS; t.roofStairs = B.JUNGLE_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_STONEBRICK);
        t.fence = B.s(B.JUNGLE_FENCE);
        t.lamp = B.s(B.GLOWSTONE);
        t.glass = B.s(B.STAINED_GLASS, 5);
        t.accent = B.s(B.WOOL, 5); t.accentAlt = B.s(B.STAINED_CLAY, 13);
        t.liquid = B.s(B.WATER); t.liquidBed = B.s(B.DIRT, 1);
        t.crate = B.s(B.LOG, B.JUNGLE | 4);
        t.tree = Theme.TreeStyle.JUNGLE; t.vines = true;
        t.log = B.s(B.LOG, B.JUNGLE); t.leaves = B.s(B.LEAVES, B.JUNGLE | 4);
        t.flora = new int[]{B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 2), B.s(B.TALL_GRASS, 2),
                B.s(B.DOUBLE_PLANT, 3), B.s(B.DOUBLE_PLANT, 2), B.s(B.RED_FLOWER, 1), B.s(B.MELON)};
        t.floraDensity = 0.34;
        t.treeDensity = 1.4;
        t.midFeature = Theme.MidFeature.GREAT_TREE;
        return t;
    }

    /** Netherrack, nether brick fortress and lava (Pernicious-like). */
    public static Theme nether() {
        Theme t = new Theme("nether");
        t.biome = Biome.HELL;
        t.top = B.s(B.NETHERRACK); t.sub = B.s(B.NETHERRACK); t.core = B.s(B.NETHERRACK);
        t.coreVariants = new int[]{B.s(B.QUARTZ_ORE), B.s(B.SOUL_SAND), B.s(B.GRAVEL), B.s(B.NETHER_BRICK)};
        t.surfacePatches = new int[]{B.s(B.SOUL_SAND)};
        t.floor = B.s(B.NETHER_BRICK); t.floorAlt = B.s(B.OBSIDIAN); t.floorTrim = B.s(B.STAINED_CLAY, 15);
        t.path = B.s(B.NETHER_BRICK); t.pathAlt = B.s(B.STAINED_CLAY, 14);
        t.wall = B.s(B.NETHER_BRICK); t.wallAlt = B.s(B.STAINED_CLAY, 14); t.pillar = B.s(B.NETHER_BRICK);
        t.roof = B.s(B.NETHER_BRICK); t.roofSlab = B.s(B.SLAB, B.SLAB_NETHER);
        t.stairs = B.NETHER_BRICK_STAIRS; t.roofStairs = B.NETHER_BRICK_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_NETHER);
        t.fence = B.s(B.NETHER_FENCE);
        t.lamp = B.s(B.GLOWSTONE);
        t.glass = B.s(B.STAINED_GLASS, 14);
        t.accent = B.s(B.WOOL, 14); t.accentAlt = B.s(B.STAINED_CLAY, 1);
        t.liquid = B.s(B.LAVA); t.liquidBed = B.s(B.OBSIDIAN);
        t.crate = B.s(B.LOG2, 1 | 4);
        t.tree = Theme.TreeStyle.NETHER;
        t.log = B.s(B.NETHER_BRICK); t.leaves = B.s(B.NETHERRACK);
        t.flora = new int[]{B.s(B.RED_MUSHROOM), B.s(B.BROWN_MUSHROOM), B.s(B.NETHER_WART, 3), B.s(B.NETHER_WART, 3)};
        t.floraDensity = 0.07;
        t.treeDensity = 0.8;
        t.ores = new int[]{B.s(B.QUARTZ_ORE), B.s(B.GOLD_ORE), B.s(B.GLOWSTONE)};
        t.midFeature = Theme.MidFeature.FORTRESS;
        return t;
    }

    /** Classic green SkyWars islands with oak/birch, cobble and a windmill at mid. */
    public static Theme meadow() {
        Theme t = new Theme("meadow");
        t.biome = Biome.FOREST;
        t.coreVariants = new int[]{B.s(B.STONE, 5), B.s(B.GRAVEL), B.s(B.COBBLE), B.s(B.DIRT)};
        t.surfacePatches = new int[]{B.s(B.DIRT, 1)};
        t.floor = B.s(B.COBBLE); t.floorAlt = B.s(B.MOSSY_COBBLE); t.floorTrim = B.s(B.STONE_BRICK);
        t.path = B.s(B.GRAVEL); t.pathAlt = B.s(B.COBBLE);
        t.wall = B.s(B.PLANKS, B.OAK); t.wallAlt = B.s(B.COBBLE); t.pillar = B.s(B.LOG, B.OAK);
        t.roof = B.s(B.PLANKS, B.SPRUCE); t.roofSlab = B.s(B.WOOD_SLAB, B.SPRUCE);
        t.stairs = B.COBBLE_STAIRS; t.roofStairs = B.SPRUCE_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_COBBLE);
        t.fence = B.s(B.FENCE);
        t.tree = Theme.TreeStyle.OAK;
        t.flora = new int[]{B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.TALL_GRASS, 1), B.s(B.RED_FLOWER, 0),
                B.s(B.YELLOW_FLOWER), B.s(B.RED_FLOWER, 3), B.s(B.RED_FLOWER, 6), B.s(B.DOUBLE_PLANT, 2)};
        t.floraDensity = 0.3;
        t.midFeature = Theme.MidFeature.WINDMILL;
        return t;
    }

    /** Medieval hamlet: dark oak, stone bricks, a shrine at mid. */
    public static Theme shire() {
        Theme t = meadow();
        Theme s = new Theme("shire");
        copy(t, s);
        s.biome = Biome.ROOFED_FOREST;
        s.tree = Theme.TreeStyle.DARK_OAK;
        s.log = B.s(B.LOG2, 1); s.leaves = B.s(B.LEAVES2, 1 | 4);
        s.floor = B.s(B.STONE_BRICK); s.floorAlt = B.s(B.STONE_BRICK, 1); s.floorTrim = B.s(B.STONE_BRICK, 3);
        s.wall = B.s(B.PLANKS, B.DARK_OAK); s.pillar = B.s(B.LOG2, 1); s.roof = B.s(B.BRICK);
        s.roofStairs = B.BRICK_STAIRS; s.stairs = B.STONE_BRICK_STAIRS; s.slab = B.s(B.SLAB, B.SLAB_STONEBRICK);
        s.fence = B.s(B.DARK_OAK_FENCE);
        s.midFeature = Theme.MidFeature.SHRINE;
        return s;
    }

    /** Sand, sandstone, acacia and cactus with an obelisk at mid. */
    public static Theme desert() {
        Theme t = new Theme("desert");
        t.biome = Biome.DESERT;
        t.top = B.s(B.SAND); t.sub = B.s(B.SANDSTONE); t.core = B.s(B.SANDSTONE);
        t.coreVariants = new int[]{B.s(B.STONE), B.s(B.HARD_CLAY), B.s(B.STAINED_CLAY, 1), B.s(B.STAINED_CLAY, 4)};
        t.surfacePatches = new int[]{B.s(B.SAND, 1)};
        t.subDepth = 3;
        t.floor = B.s(B.SANDSTONE, 2); t.floorAlt = B.s(B.SANDSTONE, 1); t.floorTrim = B.s(B.RED_SANDSTONE, 2);
        t.path = B.s(B.SANDSTONE, 2); t.pathAlt = B.s(B.SAND, 1);
        t.wall = B.s(B.SANDSTONE, 2); t.wallAlt = B.s(B.RED_SANDSTONE); t.pillar = B.s(B.SANDSTONE, 1);
        t.roof = B.s(B.HARD_CLAY); t.roofSlab = B.s(B.SLAB, B.SLAB_SANDSTONE);
        t.stairs = B.SANDSTONE_STAIRS; t.roofStairs = B.RED_SANDSTONE_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_SANDSTONE);
        t.fence = B.s(B.ACACIA_FENCE);
        t.lamp = B.s(B.GLOWSTONE);
        t.accent = B.s(B.WOOL, 1); t.accentAlt = B.s(B.STAINED_CLAY, 4);
        t.liquid = B.s(B.WATER); t.liquidBed = B.s(B.SAND);
        t.crate = B.s(B.HAY);
        t.tree = Theme.TreeStyle.ACACIA;
        t.log = B.s(B.LOG2, 0); t.leaves = B.s(B.LEAVES2, 4);
        t.flora = new int[]{B.s(B.DEAD_BUSH), B.s(B.DEAD_BUSH), B.s(B.CACTUS)};
        t.floraDensity = 0.07;
        t.treeDensity = 0.6;
        t.midFeature = Theme.MidFeature.OBELISK;
        return t;
    }

    /** Snow-covered spruce islands with packed ice and an ice spire at mid. */
    public static Theme snow() {
        Theme t = new Theme("snow");
        t.biome = Biome.ICE_PLAINS;
        t.top = B.s(B.GRASS); t.sub = B.s(B.DIRT); t.core = B.s(B.STONE);
        t.coreVariants = new int[]{B.s(B.PACKED_ICE), B.s(B.STONE, 5), B.s(B.GRAVEL), B.s(B.COBBLE)};
        t.surfacePatches = new int[]{B.s(B.SNOW_BLOCK), B.s(B.PACKED_ICE)};
        t.snowCover = true;
        t.floor = B.s(B.STONE_BRICK); t.floorAlt = B.s(B.PACKED_ICE); t.floorTrim = B.s(B.QUARTZ_BLOCK);
        t.path = B.s(B.GRAVEL); t.pathAlt = B.s(B.SNOW_BLOCK);
        t.wall = B.s(B.PLANKS, B.SPRUCE); t.wallAlt = B.s(B.STONE_BRICK); t.pillar = B.s(B.LOG, B.SPRUCE);
        t.roof = B.s(B.PLANKS, B.DARK_OAK); t.roofSlab = B.s(B.WOOD_SLAB, B.DARK_OAK);
        t.stairs = B.STONE_BRICK_STAIRS; t.roofStairs = B.DARK_OAK_STAIRS;
        t.slab = B.s(B.SLAB, B.SLAB_STONEBRICK);
        t.fence = B.s(B.SPRUCE_FENCE);
        t.lamp = B.s(B.SEA_LANTERN);
        t.glass = B.s(B.STAINED_GLASS, 0);
        t.accent = B.s(B.WOOL, 0); t.accentAlt = B.s(B.STAINED_CLAY, 3);
        t.liquid = B.s(B.ICE); t.liquidBed = B.s(B.PACKED_ICE);
        t.crate = B.s(B.LOG, B.SPRUCE | 4);
        t.tree = Theme.TreeStyle.SPRUCE;
        t.log = B.s(B.LOG, B.SPRUCE); t.leaves = B.s(B.LEAVES, B.SPRUCE | 4);
        t.flora = new int[]{B.s(B.TALL_GRASS, 2)};
        t.floraDensity = 0.1;
        t.midFeature = Theme.MidFeature.ICE_SPIRE;
        return t;
    }

    private static void copy(Theme a, Theme b) {
        b.biome = a.biome; b.top = a.top; b.sub = a.sub; b.core = a.core; b.coreVariants = a.coreVariants; b.rim = a.rim;
        b.surfacePatches = a.surfacePatches; b.subDepth = a.subDepth; b.floor = a.floor; b.floorAlt = a.floorAlt;
        b.floorTrim = a.floorTrim; b.path = a.path; b.pathAlt = a.pathAlt; b.wall = a.wall; b.wallAlt = a.wallAlt;
        b.pillar = a.pillar; b.roof = a.roof; b.slab = a.slab; b.roofSlab = a.roofSlab; b.stairs = a.stairs;
        b.roofStairs = a.roofStairs; b.fence = a.fence; b.wallPost = a.wallPost; b.lamp = a.lamp; b.glass = a.glass;
        b.accent = a.accent; b.accentAlt = a.accentAlt; b.liquid = a.liquid; b.liquidBed = a.liquidBed; b.crate = a.crate;
        b.tree = a.tree; b.log = a.log; b.leaves = a.leaves; b.flora = a.flora; b.floraDensity = a.floraDensity;
        b.treeDensity = a.treeDensity; b.snowCover = a.snowCover; b.vines = a.vines; b.ores = a.ores; b.midFeature = a.midFeature;
    }
}
