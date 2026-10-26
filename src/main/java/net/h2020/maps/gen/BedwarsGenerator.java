package net.h2020.maps.gen;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.TeamColor;
import net.h2020.api.map.MapKeys;

import java.util.Random;

/**
 * Procedural Hypixel-style Bed Wars map.
 *
 * <pre>
 * Layout (top view, north = -z). 8 teams: two islands per side, clockwise from the north-west slot
 * (Red, Blue | Green, Yellow | Aqua, White | Pink, Gray). 4 teams: one island per side (Red N, Blue E,
 * Green S, Yellow W). Diamond islands on the four diagonals between neighbouring teams, a large mid island
 * with a raised centre platform, a themed landmark and the emerald generators (4 on 8-team maps, 2 on 4-team).
 *
 *   8-team: team centre 78 blocks from mid (+-28 lateral), diamond islands at (+-66, +-66) ~25-30 block gaps
 *   4-team: team centre 64 blocks from mid, diamond islands at (+-42, +-42)
 *
 * Team island (local frame, f+ = towards mid, s+ = right), surface at TOP:
 *   f -10..-6  generator forge (back wall + furnaces, canopy with team wool), generator at (0,-8)
 *   f  -3      spawn (looking at mid)
 *   s +-6,f -3 item shop (right) / team upgrades (left) NPC stalls with team-coloured awnings
 *   f  4..5    bed (foot at 4, head at 5 pointing at mid) on a team-clay framed platform
 *   f  7..11   path to the island edge, banner poles either side
 * </pre>
 */
public final class BedwarsGenerator {
    public static final int TOP = 65;
    public static final int BUILD_ABOVE = 26;

    private BedwarsGenerator() {}

    public static GeneratedMap generate(String name, Theme t, int teams, long seed) {
        GeneratedMap m = new GeneratedMap(name, GameFamily.BEDWARS);
        m.teamCounts.add(teams);
        m.biome = t.biome;
        m.theme = t.id;
        m.buildLimitY = TOP + BUILD_ABOVE;
        Random r = new Random(seed);
        Noise n = new Noise(seed);
        BlockCanvas c = m.canvas;
        boolean eight = teams > 4;
        int dist = eight ? 78 : 64, lateral = eight ? 28 : 0, diag = eight ? 66 : 42;

        // ---- team islands, clockwise
        Dir[] sides = {Dir.SOUTH, Dir.WEST, Dir.NORTH, Dir.EAST}; // forward (towards mid) of N, E, S, W islands
        int idx = 0;
        for (Dir fwd : sides) {
            int[] lats = eight ? new int[]{lateral, -lateral} : new int[]{0};
            for (int lat : lats) {
                if (idx >= teams || idx >= TeamColor.values().length) break;
                TeamColor team = TeamColor.values()[idx++];
                Dir right = fwd.cw();
                int cx = -fwd.dx * dist + right.dx * lat;
                int cz = -fwd.dz * dist + right.dz * lat;
                teamIsland(m, t, r, n, new Frame(cx, TOP, cz, fwd), team);
            }
        }

        // ---- diamond islands
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) diamondIsland(m, t, r, n, sx * diag, sz * diag);

        // ---- mid
        midIsland(m, t, r, n, eight);

        // ---- vegetation on every exposed soil block at island level
        Decor.flora(c, t, r, c.minX(), c.minZ(), c.maxX(), c.maxZ(), TOP - 2, TOP + 1, t.floraDensity);

        m.boundsFromCanvas(22);

        // ---- waiting lobby + spectator
        waitingLobby(m, t, teams);
        m.point(MapKeys.SPECTATOR, 0.5, TOP + 30, 0.5, 0f);
        m.meta.put("layout", eight ? "bedwars-8" : "bedwars-4");
        return m;
    }

    // =========================================================================================== team island
    private static void teamIsland(GeneratedMap m, Theme t, Random r, Noise n, Frame fr, TeamColor team) {
        BlockCanvas c = m.canvas;
        int wool = team.woolData();
        int teamWool = B.s(B.WOOL, wool), teamClay = B.s(B.STAINED_CLAY, wool);
        Dir fwd = fr.fwd();

        Shapes.island(c, t, n, fr.ox + 0.5, TOP, fr.oz + 0.5, 11, 11, 8, 0.7, 0.06, false);
        // guarantee a full footprint under everything we build
        for (int s = -8; s <= 8; s++)
            for (int f = -11; f <= 11; f++) {
                if (Math.abs(s) > 2 && f > 7) continue;
                int x = fr.x(s, f), z = fr.z(s, f);
                if (c.isAir(x, TOP, z)) {
                    c.set(x, TOP, z, t.top);
                    Shapes.support(c, x, TOP - 1, z, 3, t.sub);
                }
            }

        // base pad
        for (int s = -7; s <= 7; s++)
            for (int f = -10; f <= 3; f++) {
                boolean border = Math.abs(s) == 7 || f == -10 || f == 3;
                int st = border ? t.floorTrim : (Math.abs(s) % 4 == 0 && Math.abs(f) % 4 == 0 ? t.floorAlt : t.floor);
                fr.set(c, s, 0, f, st);
            }
        fr.set(c, 7, 0, -10, teamClay); fr.set(c, -7, 0, -10, teamClay);
        fr.set(c, 7, 0, 3, teamClay); fr.set(c, -7, 0, 3, teamClay);

        // bed platform: team clay frame, floor inside
        for (int s = -2; s <= 2; s++)
            for (int f = 3; f <= 6; f++) {
                boolean edge = Math.abs(s) == 2 || f == 6;
                fr.set(c, s, 0, f, edge ? teamClay : t.floor);
            }
        fr.set(c, 0, 1, 4, B.s(B.BED, fwd.bed()));
        fr.set(c, 0, 1, 5, B.s(B.BED, fwd.bed() | 8));

        // path to the front edge
        for (int f = 7; f <= 12; f++)
            for (int s = -1; s <= 1; s++) {
                int x = fr.x(s, f), z = fr.z(s, f);
                if (c.isGround(x, TOP, z)) c.set(x, TOP, z, r.nextInt(4) == 0 ? t.pathAlt : t.path);
            }

        // generator forge
        for (int s = -2; s <= 2; s++) for (int f = -9; f <= -7; f++) fr.set(c, s, 0, f, t.floorAlt);
        fr.set(c, 0, 0, -8, t.floorTrim);
        for (int s = -3; s <= 3; s++) {
            for (int y = 1; y <= 3; y++) fr.set(c, s, y, -10, t.wallAlt);
            fr.set(c, s, 0, -11, t.floorTrim);
        }
        fr.set(c, -1, 1, -10, B.s(B.FURNACE, fwd.facing()));
        fr.set(c, 1, 1, -10, B.s(B.FURNACE, fwd.facing()));
        fr.set(c, 0, 1, -10, teamClay);
        fr.set(c, 0, 2, -10, B.s(B.IRON_BARS));
        for (int s = -3; s <= 3; s += 6) for (int y = 1; y <= 3; y++) fr.set(c, s, y, -6, t.fence);
        for (int s = -3; s <= 3; s++)
            for (int f = -10; f <= -6; f++) fr.set(c, s, 4, f, s == 0 ? teamWool : t.roofSlab);
        fr.set(c, -2, 4, -8, t.lamp);
        fr.set(c, 2, 4, -8, t.lamp);

        // shop (right) & upgrades (left) stalls
        stall(c, t, fr, 1, teamWool, B.s(B.WORKBENCH), B.s(B.HAY));
        stall(c, t, fr, -1, teamWool, B.s(B.BOOKSHELF), B.s(B.ENCHANT_TABLE));

        // lamp posts on the pad's front corners, banner poles along the path
        for (int s = -7; s <= 7; s += 14) {
            int x = fr.x(s, 5), z = fr.z(s, 5);
            int gy = c.surface(x, z, TOP + 1);
            if (gy >= TOP - 1) {
                Decor.lampPost(c, t, x, gy, z, 3);
                c.set(x, gy + 5, z, B.s(B.CARPET, wool));
            }
        }
        for (int s = -3; s <= 3; s += 6) {
            int x = fr.x(s, 9), z = fr.z(s, 9);
            int gy = c.surface(x, z, TOP + 1);
            if (gy >= TOP - 1) Decor.bannerPole(c, t, x, gy, z, 3, fwd, Decor.woolToDye(wool));
        }

        // vegetation and props in the island corners
        int[][] treeSpots = {{9, -8}, {-9, -8}, {8, 8}, {-8, 8}};
        for (int[] ts : treeSpots) {
            if (r.nextDouble() > 0.75 * t.treeDensity + 0.15) continue;
            int x = fr.x(ts[0], ts[1]), z = fr.z(ts[0], ts[1]);
            int gy = c.surface(x, z, TOP + 1);
            if (gy >= TOP - 1 && B.id(c.get(x, gy, z)) != B.id(t.floor)) Decor.tree(c, t, r, x, gy, z);
        }
        int cx = fr.x(-5, -8), cz = fr.z(-5, -8);
        Decor.crates(c, t, r, cx, TOP, cz);
        int px = fr.x(5, -9), pz = fr.z(5, -9);
        c.set(px, TOP + 1, pz, B.s(B.HAY));
        c.pot(fr.x(4, 2), TOP + 1, fr.z(4, 2), B.RED_FLOWER, 0);
        c.pot(fr.x(-4, 2), TOP + 1, fr.z(-4, 2), B.YELLOW_FLOWER, 0);

        // ---- points
        String sp = MapKeys.teamSpawn(team);
        m.stand(sp, fr.x(0, -3), TOP + 1, fr.z(0, -3), fwd.yaw());
        m.block(MapKeys.teamBed(team), fr.x(0, 4), TOP + 1, fr.z(0, 4));
        m.block(MapKeys.teamBed(team), fr.x(0, 5), TOP + 1, fr.z(0, 5));
        m.stand(MapKeys.teamGenerator(team), fr.x(0, -8), TOP + 1, fr.z(0, -8), fwd.yaw());
        float shopYaw = Dir.yawTowards(fr.xd(6, -3), fr.zd(6, -3), fr.xd(0, -3), fr.zd(0, -3));
        float upYaw = Dir.yawTowards(fr.xd(-6, -3), fr.zd(-6, -3), fr.xd(0, -3), fr.zd(0, -3));
        m.stand(MapKeys.teamShop(team), fr.x(6, -3), TOP + 1, fr.z(6, -3), shopYaw);
        m.stand(MapKeys.teamUpgrades(team), fr.x(-6, -3), TOP + 1, fr.z(-6, -3), upYaw);
        m.box(MapKeys.teamBase(team), fr.x(-13, -13), TOP - 12, fr.z(-13, -13), fr.x(13, 13), TOP + 14, fr.z(13, 13));
    }

    /**
     * NPC stall on the side {@code side} (+1 right = shop, -1 left = upgrades): back wall behind the NPC, fence
     * posts in front, team-coloured striped awning, two props on the wall.
     */
    private static void stall(BlockCanvas c, Theme t, Frame fr, int side, int teamWool, int propA, int propB) {
        int wallS = 8 * side;
        for (int f = -5; f <= -1; f++) {
            for (int y = 1; y <= 2; y++) fr.set(c, wallS, y, f, (f == -5 || f == -1) ? t.pillar : t.wall);
            fr.set(c, wallS, 3, f, teamWool);
            for (int s = 5; s <= 8; s++) fr.set(c, s * side, 4, f, (f & 1) == 0 ? teamWool : t.accent);
        }
        for (int y = 1; y <= 3; y++) {
            fr.set(c, 5 * side, y, -5, t.fence);
            fr.set(c, 5 * side, y, -1, t.fence);
        }
        fr.set(c, 7 * side, 1, -5, propA);
        fr.set(c, 7 * side, 1, -1, propB);
        fr.set(c, 7 * side, 3, -3, t.lamp);
        fr.set(c, 6 * side, 0, -3, t.floorAlt);
    }

    // =========================================================================================== diamond
    private static void diamondIsland(GeneratedMap m, Theme t, Random r, Noise n, int cx, int cz) {
        BlockCanvas c = m.canvas;
        Shapes.island(c, t, n, cx + 0.5, TOP, cz + 0.5, 5.5, 5.5, 6, 0.3, 0.14, false);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, TOP, cz + dz, t.floorTrim);
        c.set(cx, TOP, cz, B.s(B.DIAMOND_BLOCK));
        for (Dir d : Dir.values()) c.set(cx + d.dx * 2, TOP, cz + d.dz * 2, t.floor);
        // props: a lamp and a small tree/bush away from the generator
        Dir away = Dir.toward(cx, cz);
        int lx = cx + away.dx * 3 + away.cw().dx * 2, lz = cz + away.dz * 3 + away.cw().dz * 2;
        int gy = c.surface(lx, lz, TOP + 1);
        if (gy >= TOP - 1) Decor.lampPost(c, t, lx, gy, lz, 2);
        int tx = cx + away.dx * 3 - away.cw().dx * 2, tz = cz + away.dz * 3 - away.cw().dz * 2;
        gy = c.surface(tx, tz, TOP + 1);
        if (gy >= TOP - 1) {
            if (t.tree == Theme.TreeStyle.NONE || r.nextBoolean()) Decor.bush(c, t, r, tx, gy, tz);
            else Decor.tree(c, t, r, tx, gy, tz);
        }
        m.stand(MapKeys.DIAMOND_GEN, cx, TOP + 1, cz, 0f);
    }

    // =========================================================================================== mid
    private static void midIsland(GeneratedMap m, Theme t, Random r, Noise n, boolean eight) {
        BlockCanvas c = m.canvas;
        int rm = eight ? 15 : 17;
        Shapes.island(c, t, n, 0.5, TOP, 0.5, rm, rm, 13, 0.35, 0.08, false);
        for (Dir d : Dir.values())
            Shapes.island(c, t, n, d.dx * (rm + 3) + 0.5, TOP, d.dz * (rm + 3) + 0.5, 6.5, 6.5, 7, 0.2, 0.15, false);

        int platR = t.midFeature == Theme.MidFeature.DOME ? 9 : 8;
        double p = 4;
        int platY = TOP + 3;
        for (int x = -platR - 1; x <= platR + 1; x++)
            for (int z = -platR - 1; z <= platR + 1; z++) {
                if (!Shapes.inside(x, z, platR, p)) continue;
                boolean edge = !Shapes.inside(x, z, platR - 1, p);
                for (int y = TOP + 1; y < platY; y++) c.set(x, y, z, t.wallAlt);
                c.set(x, platY, z, edge ? t.floorTrim : ((x + z) % 5 == 0 ? t.floorAlt : t.floor));
            }
        // ramps on all four sides
        for (Dir d : Dir.values()) {
            Dir side = d.cw();
            for (int i = 0; i < 3; i++) {
                int k = platR + 1 + i, y = platY - i;
                for (int l = -1; l <= 1; l++) {
                    int x = d.dx * k + side.dx * l, z = d.dz * k + side.dz * l;
                    c.set(x, y, z, t.stairs(d.opposite()));
                    for (int yy = TOP + 1; yy < y; yy++) c.set(x, yy, z, t.wallAlt);
                }
            }
            // lamps flanking each ramp at the platform edge
            for (int l = -2; l <= 2; l += 4) {
                int x = d.dx * (platR - 1) + side.dx * l, z = d.dz * (platR - 1) + side.dz * l;
                if (Shapes.inside(x, z, platR, p)) Decor.lampPost(c, t, x, platY, z, 2);
            }
        }

        // emerald generators
        int[][] em = eight ? new int[][]{{5, 5}, {-5, 5}, {5, -5}, {-5, -5}} : new int[][]{{5, 5}, {-5, -5}};
        for (int[] e : em) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(e[0] + dx, platY, e[1] + dz, t.floorAlt);
            c.set(e[0], platY, e[1], B.s(B.EMERALD_BLOCK));
            m.stand(MapKeys.EMERALD_GEN, e[0], platY + 1, e[1], 0f);
        }

        int clear = Features.bedwarsCentre(c, t, r, 0, platY + 1, 0);
        m.stand(MapKeys.MID, 0, platY + 1, Math.min(platR - 1, clear + 2), 180f);

        // ground ring decoration: lamps between the ramps, trees on the diagonals, benches/crates on the wings
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + 45 * i);
            int x = (int) Math.round(Math.cos(a) * (rm - 2.5)), z = (int) Math.round(Math.sin(a) * (rm - 2.5));
            int gy = c.surface(x, z, TOP + 1);
            if (gy == TOP && c.isAir(x, TOP + 1, z)) {
                if (i % 2 == 0) Decor.lampPost(c, t, x, gy, z, 3);
                else Decor.rocks(c, r, x, gy, z, new int[]{t.wallAlt, t.slab, t.core});
            }
        }
        int tr = rm - 6;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = sx * tr, z = sz * tr;
                int gy = c.surface(x, z, TOP + 1);
                if (gy == TOP && c.isAir(x, TOP + 1, z)) Decor.tree(c, t, r, x, gy, z);
            }
        Dir[] dirs = Dir.values();
        for (int i = 0; i < 4; i++) {
            Dir d = dirs[i];
            int wx = d.dx * (rm + 3), wz = d.dz * (rm + 3);
            if (i == 0 && t.liquid != 0) pond(c, t, wx, wz);
            else if (i == 1) {
                Decor.bench(c, t, wx + d.cw().dx * 2, TOP, wz + d.cw().dz * 2, d.opposite());
                Decor.crates(c, t, r, wx - d.cw().dx * 2, TOP, wz - d.cw().dz * 2);
            } else if (i == 2) {
                int gy = c.surface(wx, wz, TOP + 1);
                if (gy >= TOP - 1) Decor.tree(c, t, r, wx, gy, wz);
            } else {
                Decor.hangingLamp(c, t, wx, TOP, wz, d.opposite(), 3);
                Decor.crates(c, t, r, wx + d.cw().dx * 2, TOP, wz + d.cw().dz * 2);
            }
        }
    }

    /** Small liquid pool (water/lava/ice per theme) set into the ground with a trimmed rim. */
    static void pond(BlockCanvas c, Theme t, int cx, int cz) {
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 2.2) {
                    c.set(cx + x, TOP, cz + z, t.liquid);
                    c.set(cx + x, TOP - 1, cz + z, t.liquidBed);
                    c.set(cx + x, TOP + 1, cz + z, 0);
                    if (B.id(t.liquid) == B.WATER && (x + z) % 3 == 0 && d > 1) c.set(cx + x, TOP + 1, cz + z, B.s(B.LILY_PAD));
                } else if (d <= 3.2) {
                    c.set(cx + x, TOP, cz + z, t.floorTrim);
                    Shapes.support(c, cx + x, TOP - 1, cz + z, 2, t.sub);
                }
            }
    }

    // =========================================================================================== waiting lobby
    private static void waitingLobby(GeneratedMap m, Theme t, int teams) {
        BlockCanvas c = m.canvas;
        int wy = m.buildLimitY + 14;
        int half = 10;
        TeamColor[] colors = TeamColor.values();
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                int ring = Math.max(Math.abs(x), Math.abs(z));
                int st;
                if (ring == half) st = t.floorTrim;
                else if (ring == 0) st = t.lamp;
                else if (ring % 3 == 0) {
                    // team-coloured ring segments
                    double a = Math.atan2(z, x);
                    int seg = (int) Math.floor(((a + Math.PI) / (2 * Math.PI)) * teams) % teams;
                    st = B.s(B.STAINED_CLAY, colors[seg].woolData());
                } else st = ((x + z) & 1) == 0 ? B.s(B.STAINED_CLAY, 0) : B.s(B.STAINED_CLAY, 8);
                c.set(x, wy, z, st);
                c.set(x, wy - 1, z, t.floorTrim);
            }
        for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) c.set(sx * 6, wy, sz * 6, t.lamp);
        for (int i = -half; i <= half; i++)
            for (int y = 1; y <= 4; y++) {
                c.set(i, wy + y, -half, t.glass); c.set(i, wy + y, half, t.glass);
                c.set(-half, wy + y, i, t.glass); c.set(half, wy + y, i, t.glass);
            }
        m.stand(MapKeys.WAITING_LOBBY, 0, wy + 1, 0, 0f);
        m.box("waiting.lobby.bounds", -half - 1, wy - 1, -half - 1, half + 1, wy + 5, half + 1);
        m.spawn = new double[]{0.5, wy + 1, 0.5, 0};
    }
}
