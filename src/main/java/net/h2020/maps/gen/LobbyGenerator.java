package net.h2020.maps.gen;

import net.h2020.api.game.TeamColor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Hub worlds: the main lobby and the Bed Wars / SkyWars lobbies.
 *
 * <pre>
 * MAIN LOBBY (world "h2020_lobby"): plaza surface y=70, world spawn (0.5, 71, 0.5) yaw 180 (looking north).
 * Eight clear NPC pads on a ring (radius ~9.8) around spawn, NPC feet at y=71, each NPC looks at spawn:
 *   bedwars        (-3.5, 71, -8.5)     front-left of spawn
 *   skywars        ( 4.5, 71, -8.5)     front-right of spawn
 *   duels          (-8.5, 71, -3.5)     murder_mystery ( 9.5, 71, -3.5)
 *   build_battle   (-8.5, 71,  4.5)     uhc            ( 9.5, 71,  4.5)
 *   arcade         (-3.5, 71,  9.5)     tnt_games      ( 4.5, 71,  9.5)
 * Fountain at (0, 70, -25) on the north avenue, four avenues to the island edge, pavilions on the diagonals.
 *
 * BED WARS LOBBY ("h2020_lobby_bedwars"): surface y=70, spawn (0.5, 71, 0.5) yaw 180, mode NPC pads:
 *   bedwars_eight_one (-6.5,71,-7.5)  bedwars_eight_two (-2.5,71,-9.5)
 *   bedwars_four_three (3.5,71,-9.5)  bedwars_four_four (7.5,71,-7.5)
 * SKYWARS LOBBY ("h2020_lobby_skywars"): same spawn convention, pads:
 *   solo_normal (-6.5,71,-7.5) solo_insane (-2.5,71,-9.5) teams_normal (3.5,71,-9.5) teams_insane (7.5,71,-7.5)
 * </pre>
 * The same positions are written to plugins/Hypixel2020/lobby-npcs.yml (see {@link #npcPads(String)}).
 */
public final class LobbyGenerator {
    public static final int PY = 70;
    public static final String MAIN = "h2020_lobby", BEDWARS = "h2020_lobby_bedwars", SKYWARS = "h2020_lobby_skywars";

    private LobbyGenerator() {}

    /** Fixed NPC pad positions per lobby world: name -> {x,y,z,yaw}. Spawn is always (0.5, 71, 0.5). */
    public static Map<String, double[]> npcPads(String world) {
        Map<String, double[]> m = new LinkedHashMap<String, double[]>();
        if (MAIN.equals(world)) {
            pad(m, "bedwars", -4, -9); pad(m, "skywars", 4, -9);
            pad(m, "duels", -9, -4); pad(m, "murder_mystery", 9, -4);
            pad(m, "build_battle", -9, 4); pad(m, "uhc", 9, 4);
            pad(m, "arcade", -4, 9); pad(m, "tnt_games", 4, 9);
        } else if (BEDWARS.equals(world)) {
            pad(m, "bedwars_eight_one", -7, -8); pad(m, "bedwars_eight_two", -3, -10);
            pad(m, "bedwars_four_three", 3, -10); pad(m, "bedwars_four_four", 7, -8);
        } else if (SKYWARS.equals(world)) {
            pad(m, "solo_normal", -7, -8); pad(m, "solo_insane", -3, -10);
            pad(m, "teams_normal", 3, -10); pad(m, "teams_insane", 7, -8);
        }
        return m;
    }

    private static void pad(Map<String, double[]> m, String name, int bx, int bz) {
        double x = bx + 0.5, z = bz + 0.5;
        m.put(name, new double[]{x, PY + 1, z, Dir.yawTowards(x, z, 0.5, 0.5)});
    }

    public static Theme lobbyTheme() {
        Theme t = Themes.meadow();
        t.floor = B.s(B.STONE_BRICK); t.floorAlt = B.s(B.STONE, 6); t.floorTrim = B.s(B.QUARTZ_BLOCK);
        t.path = B.s(B.STONE_BRICK); t.pathAlt = B.s(B.STONE, 6);
        t.wall = B.s(B.QUARTZ_BLOCK); t.wallAlt = B.s(B.STONE_BRICK); t.pillar = B.s(B.QUARTZ_BLOCK, 2);
        t.roof = B.s(B.STAINED_CLAY, 11); t.roofStairs = B.DARK_OAK_STAIRS; t.roofSlab = B.s(B.WOOD_SLAB, B.DARK_OAK);
        t.stairs = B.QUARTZ_STAIRS; t.slab = B.s(B.SLAB, B.SLAB_QUARTZ);
        t.fence = B.s(B.DARK_OAK_FENCE);
        t.lamp = B.s(B.SEA_LANTERN);
        t.tree = Theme.TreeStyle.OAK;
        t.biome = org.bukkit.block.Biome.PLAINS;
        return t;
    }

    // ============================================================================================ main lobby
    public static GeneratedMap main(long seed) {
        Theme t = lobbyTheme();
        GeneratedMap m = new GeneratedMap("Main Lobby", null);
        m.biome = t.biome;
        Random r = new Random(seed);
        Noise n = new Noise(seed);
        BlockCanvas c = m.canvas;

        Shapes.island(c, t, n, 0.5, PY, 0.5, 64, 64, 18, 0.15, 0.08, false);

        // plaza
        for (int x = -17; x <= 17; x++)
            for (int z = -17; z <= 17; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 16.5) continue;
                int st;
                if (d > 15.5) st = t.floorTrim;
                else if (d < 2.5) st = d < 1 ? B.s(B.QUARTZ_BLOCK, 1) : B.s(B.QUARTZ_BLOCK);
                else if (Math.abs(d - 13) < 0.5) st = B.s(B.STONE, 6);
                else if (Math.abs(d - 5) < 0.5) st = t.floorTrim;
                else st = (((int) Math.floor(Math.atan2(z, x) * 16 / Math.PI)) & 1) == 0 ? t.floor : B.s(B.STONE_BRICK, 3);
                c.set(x, PY, z, st);
            }
        // avenues
        for (Dir d : Dir.values()) {
            Dir side = d.cw();
            for (int k = 16; k <= 60; k++)
                for (int l = -2; l <= 2; l++) {
                    int x = d.dx * k + side.dx * l, z = d.dz * k + side.dz * l;
                    if (!c.isGround(x, PY, z)) continue;
                    c.set(x, PY, z, Math.abs(l) == 2 ? t.floorTrim : (k % 6 == 0 ? t.pathAlt : t.path));
                }
            // lamps along the avenue
            for (int k = 20; k <= 56; k += 8)
                for (int l = -3; l <= 3; l += 6) {
                    if (d == Dir.NORTH && Math.abs(k - 25) <= 7) continue; // fountain
                    int x = d.dx * k + side.dx * l, z = d.dz * k + side.dz * l;
                    if (c.isGround(x, PY, z)) Decor.hangingLamp(c, t, x, PY, z, l < 0 ? side : side.opposite(), 4);
                }
        }
        // NPC pads
        int idx = 0;
        TeamColor[] cols = TeamColor.values();
        for (Map.Entry<String, double[]> e : npcPads(MAIN).entrySet()) {
            int x = (int) Math.floor(e.getValue()[0]), z = (int) Math.floor(e.getValue()[2]);
            npcPad(c, t, x, z, cols[idx++ % cols.length].woolData());
        }
        // fountain on the north avenue
        Decor.fountain(c, t, 0, PY, -25, 4, t.floorTrim, t.pillar);
        for (int i = -1; i <= 1; i += 2) {
            Decor.bench(c, t, i * 7, PY, -24, i < 0 ? Dir.EAST : Dir.WEST);
        }
        // pavilions on the diagonals
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                pavilion(c, t, r, sx * 34, PY, sz * 34, Dir.toward(-sx, -sz));
        // market stalls on the east / west avenues
        int[] stallWool = {14, 11, 4, 5};
        int si = 0;
        for (int k = 26; k <= 46; k += 10) {
            Decor.stall(c, t, r, 0 + 7, PY, k, Dir.WEST, stallWool[si++ % 4]);
            Decor.stall(c, t, r, -7, PY, -k - 6, Dir.EAST, stallWool[si++ % 4]);
        }
        // trees around the plaza and scattered
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(i * 22.5 + 11.25);
            int x = (int) Math.round(Math.cos(a) * 21), z = (int) Math.round(Math.sin(a) * 21);
            if (onAvenue(x, z)) continue;
            int gy = c.surface(x, z, PY);
            if (gy == PY && B.id(c.get(x, gy, z)) == B.GRASS) {
                if (i % 2 == 0) Decor.roundTree(c, r, x, gy, z, B.s(B.LOG, B.OAK), B.s(B.LEAVES, B.OAK | 4), 5, false);
                else Decor.roundTree(c, r, x, gy, z, B.s(B.LOG, B.BIRCH), B.s(B.LEAVES, B.BIRCH | 4), 6, false);
            }
        }
        for (int i = 0; i < 70; i++) {
            int x = r.nextInt(110) - 55, z = r.nextInt(110) - 55;
            if (Math.sqrt(x * x + z * z) < 25 || onAvenue(x, z)) continue;
            int gy = c.surface(x, z, PY);
            if (gy != PY || B.id(c.get(x, gy, z)) != B.GRASS || !c.isAir(x, gy + 1, z)) continue;
            if (!clear(c, x, gy + 1, z, 2)) continue;
            if (r.nextInt(4) == 0) Decor.bush(c, t, r, x, gy, z);
            else Decor.roundTree(c, r, x, gy, z, B.s(B.LOG, r.nextBoolean() ? B.OAK : B.BIRCH), B.s(B.LEAVES, B.OAK | 4), 4 + r.nextInt(3), false);
        }
        Decor.flora(c, t, r, c.minX(), c.minZ(), c.maxX(), c.maxZ(), PY - 2, PY, 0.25);
        m.spawn = new double[]{0.5, PY + 1, 0.5, 180};
        return m;
    }

    // ============================================================================================ bed wars lobby
    public static GeneratedMap bedwars(long seed) {
        Theme t = lobbyTheme();
        t.roof = B.s(B.STAINED_CLAY, 14); t.lamp = B.s(B.GLOWSTONE);
        t.floor = B.s(B.STAINED_CLAY, 0); t.floorAlt = B.s(B.STAINED_CLAY, 8); t.floorTrim = B.s(B.STAINED_CLAY, 14);
        GeneratedMap m = new GeneratedMap("Bed Wars Lobby", null);
        m.biome = org.bukkit.block.Biome.PLAINS;
        Random r = new Random(seed);
        Noise n = new Noise(seed);
        BlockCanvas c = m.canvas;
        Shapes.island(c, t, n, 0.5, PY, 0.5, 36, 36, 14, 0.3, 0.1, false);
        plaza(c, t, 14);
        // giant decorative bed behind spawn (south), red wool with oak frame
        for (int x = -3; x <= 3; x++)
            for (int z = 16; z <= 25; z++) {
                c.set(x, PY + 1, z, B.s(B.PLANKS, B.OAK));
                c.set(x, PY + 2, z, z <= 18 ? B.s(B.WOOL, 0) : B.s(B.WOOL, 14));
            }
        for (int x = -3; x <= 3; x++) { c.set(x, PY + 3, 16, B.s(B.PLANKS, B.OAK)); c.set(x, PY + 4, 16, B.s(B.WOOD_SLAB, B.OAK)); }
        for (int x = -3; x <= 3; x += 6) for (int z = 16; z <= 25; z += 9) c.set(x, PY + 1, z, B.s(B.LOG, B.OAK));
        // ring of team banner poles
        TeamColor[] cols = TeamColor.values();
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45 + 22.5);
            int x = (int) Math.round(Math.cos(a) * 12), z = (int) Math.round(Math.sin(a) * 12);
            Dir facing = Dir.toward(-x, -z);
            Decor.bannerPole(c, t, x, PY, z, 4, facing, Decor.woolToDye(cols[i].woolData()));
            c.set(x, PY, z, B.s(B.WOOL, cols[i].woolData()));
        }
        // resource pedestals (iron, gold, diamond, emerald)
        int[] res = {B.s(B.IRON_BLOCK), B.s(B.GOLD_BLOCK), B.s(B.DIAMOND_BLOCK), B.s(B.EMERALD_BLOCK)};
        for (int i = 0; i < 4; i++) {
            int x = -9 + i * 6, z = 9;
            c.set(x, PY + 1, z, t.floorTrim);
            c.set(x, PY + 2, z, res[i]);
            c.set(x, PY + 3, z, B.s(B.SLAB, B.SLAB_QUARTZ));
        }
        int idx = 0;
        for (Map.Entry<String, double[]> e : npcPads(BEDWARS).entrySet()) {
            npcPad(c, t, (int) Math.floor(e.getValue()[0]), (int) Math.floor(e.getValue()[2]), cols[idx++].woolData());
        }
        decorateHub(c, t, r, 18, 34);
        m.spawn = new double[]{0.5, PY + 1, 0.5, 180};
        return m;
    }

    // ============================================================================================ skywars lobby
    public static GeneratedMap skywars(long seed) {
        Theme t = lobbyTheme();
        t.roof = B.s(B.STAINED_CLAY, 3); t.lamp = B.s(B.SEA_LANTERN);
        t.floor = B.s(B.QUARTZ_BLOCK); t.floorAlt = B.s(B.STAINED_CLAY, 3); t.floorTrim = B.s(B.STAINED_CLAY, 9);
        GeneratedMap m = new GeneratedMap("SkyWars Lobby", null);
        m.biome = org.bukkit.block.Biome.FOREST;
        Random r = new Random(seed);
        Noise n = new Noise(seed);
        BlockCanvas c = m.canvas;
        Shapes.island(c, t, n, 0.5, PY, 0.5, 28, 28, 16, 0.2, 0.12, true);
        plaza(c, t, 12);
        // display cage (glass cage like the in-game ones) behind spawn
        int gz = 15;
        for (int x = -2; x <= 2; x++)
            for (int z = gz - 2; z <= gz + 2; z++)
                for (int y = 1; y <= 5; y++) {
                    boolean shell = Math.abs(x) == 2 || Math.abs(z - gz) == 2 || y == 1 || y == 5;
                    if (shell) c.set(x, PY + y, z, B.s(B.STAINED_GLASS, 3));
                }
        // floating decorative islands around the hub
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(i * 60 + 15);
            int x = (int) Math.round(Math.cos(a) * 44), z = (int) Math.round(Math.sin(a) * 44);
            int y = PY + 4 + r.nextInt(10);
            Shapes.island(c, t, n, x + 0.5, y, z + 0.5, 4 + r.nextInt(3), 4 + r.nextInt(3), 7, 0.3, 0.2, true);
            Decor.roundTree(c, r, x, y, z, B.s(B.LOG, B.OAK), B.s(B.LEAVES, B.OAK | 4), 4, false);
        }
        int idx = 0;
        int[] cols = {5, 14, 11, 1};
        for (Map.Entry<String, double[]> e : npcPads(SKYWARS).entrySet()) {
            npcPad(c, t, (int) Math.floor(e.getValue()[0]), (int) Math.floor(e.getValue()[2]), cols[idx++]);
        }
        decorateHub(c, t, r, 15, 26);
        m.spawn = new double[]{0.5, PY + 1, 0.5, 180};
        return m;
    }

    // ============================================================================================ pieces
    private static void plaza(BlockCanvas c, Theme t, int rad) {
        for (int x = -rad - 1; x <= rad + 1; x++)
            for (int z = -rad - 1; z <= rad + 1; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > rad + 0.5) continue;
                int st = d > rad - 0.5 ? t.floorTrim : d < 1.5 ? t.lamp : (((x & 1) ^ (z & 1)) == 0 ? t.floor : t.floorAlt);
                c.set(x, PY, z, st);
            }
        for (Dir d : Dir.values()) {
            Dir side = d.cw();
            for (int k = rad; k <= rad + 22; k++)
                for (int l = -1; l <= 1; l++) {
                    int x = d.dx * k + side.dx * l, z = d.dz * k + side.dz * l;
                    if (c.isGround(x, PY, z)) c.set(x, PY, z, t.path);
                }
        }
    }

    private static void decorateHub(BlockCanvas c, Theme t, Random r, int inner, int outer) {
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 + 15);
            int x = (int) Math.round(Math.cos(a) * (inner + 2)), z = (int) Math.round(Math.sin(a) * (inner + 2));
            if (Math.abs(x) <= 2 || Math.abs(z) <= 2) continue;
            int gy = c.surface(x, z, PY);
            if (gy == PY && c.isAir(x, gy + 1, z)) Decor.lampPost(c, t, x, gy, z, 3);
        }
        for (int i = 0; i < 40; i++) {
            int x = r.nextInt(outer * 2) - outer, z = r.nextInt(outer * 2) - outer;
            double d = Math.sqrt(x * x + z * z);
            if (d < inner + 4 || d > outer - 3 || Math.abs(x) <= 2 || Math.abs(z) <= 2) continue;
            int gy = c.surface(x, z, PY);
            if (gy != PY || B.id(c.get(x, gy, z)) != B.GRASS || !clear(c, x, gy + 1, z, 2)) continue;
            if (r.nextInt(3) == 0) Decor.bush(c, t, r, x, gy, z);
            else Decor.roundTree(c, r, x, gy, z, B.s(B.LOG, B.OAK), B.s(B.LEAVES, B.OAK | 4), 4 + r.nextInt(2), false);
        }
        Decor.flora(c, t, r, c.minX(), c.minZ(), c.maxX(), c.maxZ(), PY - 2, PY, 0.25);
    }

    /** Flush NPC pad: quartz centre, coloured clay ring, a lamp post behind (away from spawn). */
    private static void npcPad(BlockCanvas c, Theme t, int x, int z, int woolData) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                c.set(x + dx, PY, z + dz, (dx == 0 && dz == 0) ? B.s(B.QUARTZ_BLOCK, 1) : B.s(B.STAINED_CLAY, woolData));
                for (int y = 1; y <= 3; y++) c.set(x + dx, PY + y, z + dz, 0);
            }
        Dir away = Dir.toward(x, z);
        int lx = x + away.dx * 2, lz = z + away.dz * 2;
        c.set(lx, PY, lz, t.floorTrim);
        Decor.lampPost(c, t, lx, PY, lz, 2);
    }

    /** Open pavilion: raised floor, quartz pillars, hip roof of stairs, hanging lamp, benches inside. */
    private static void pavilion(BlockCanvas c, Theme t, Random r, int cx, int gy, int cz, Dir front) {
        int h = 5, half = 5;
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                c.set(cx + x, gy, cz + z, (Math.abs(x) == half || Math.abs(z) == half) ? t.floorTrim : t.floor);
                Shapes.support(c, cx + x, gy - 1, cz + z, 4, t.sub);
                for (int y = 1; y <= h + 6; y++) c.set(cx + x, gy + y, cz + z, 0);
            }
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                for (int y = 1; y <= h; y++) c.set(cx + sx * (half - 1), gy + y, cz + sz * (half - 1), t.pillar);
        // hip roof
        for (int layer = 0; layer <= half; layer++) {
            int e = half - layer, y = gy + h + 1 + layer;
            for (int x = -e; x <= e; x++)
                for (int z = -e; z <= e; z++) {
                    boolean edge = Math.abs(x) == e || Math.abs(z) == e;
                    if (!edge && layer < half) continue;
                    int st;
                    if (e == 0) st = t.roof;
                    else if (Math.abs(x) == e && Math.abs(z) == e) st = t.roof;
                    else if (x == e) st = t.roofStairs(Dir.WEST);
                    else if (x == -e) st = t.roofStairs(Dir.EAST);
                    else if (z == e) st = t.roofStairs(Dir.NORTH);
                    else st = t.roofStairs(Dir.SOUTH);
                    c.set(cx + x, y, cz + z, st);
                }
        }
        c.set(cx, gy + h + 1, cz, t.lamp);
        c.set(cx, gy + h, cz, t.fence);
        c.set(cx, gy + h - 1, cz, t.lamp);
        Decor.bench(c, t, cx - 1, gy, cz + 2, Dir.NORTH);
        Decor.bench(c, t, cx - 1, gy, cz - 2, Dir.SOUTH);
        c.pot(cx + 3, gy + 1, cz + 3, B.RED_FLOWER, 0);
        c.pot(cx - 3, gy + 1, cz - 3, B.YELLOW_FLOWER, 0);
    }

    private static boolean onAvenue(int x, int z) { return Math.abs(x) <= 4 || Math.abs(z) <= 4; }

    private static boolean clear(BlockCanvas c, int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (!c.isAir(x + dx, y, z + dz)) return false;
        return true;
    }
}
