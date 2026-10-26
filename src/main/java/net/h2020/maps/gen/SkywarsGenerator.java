package net.h2020.maps.gen;

import net.h2020.api.game.GameFamily;
import net.h2020.api.map.MapKeys;

import java.util.Random;

/**
 * Procedural Hypixel-style SkyWars map.
 *
 * <pre>
 * Solo: 12 spawn islands on a ring (radius 44) around a mid island; Teams: 8 larger islands (radius 46).
 * Each spawn island (local frame, f+ towards mid): cage point above f=+2 (feet 5 blocks over the surface,
 * the skywars module builds the glass cage around it), 3 chests (left, right, back), one tree at the back,
 * ores in the stone underside. Mid: raised centre platform with a themed landmark, 4 chests on the platform
 * at (+-2,+-2) and 4 on the ground at the diagonals (+-5,+-5) = 8 mid chests.
 * </pre>
 */
public final class SkywarsGenerator {
    public static final int TOP = 72;
    public static final int BUILD_ABOVE = 38;

    private SkywarsGenerator() {}

    public static GeneratedMap generate(String name, Theme t, int islands, long seed) {
        GeneratedMap m = new GeneratedMap(name, GameFamily.SKYWARS);
        m.teamCounts.add(islands);
        m.biome = t.biome;
        m.theme = t.id;
        m.buildLimitY = TOP + BUILD_ABOVE;
        Random r = new Random(seed);
        Noise n = new Noise(seed ^ 0x5EEDL);
        BlockCanvas c = m.canvas;
        boolean teams = islands <= 8;
        double ring = teams ? 46 : 44;
        double ir = teams ? 6.5 : 5.5;

        for (int i = 0; i < islands; i++) {
            double a = -Math.PI / 2 + i * (2 * Math.PI / islands);
            int cx = (int) Math.round(Math.cos(a) * ring), cz = (int) Math.round(Math.sin(a) * ring);
            int top = TOP + (r.nextInt(3) - 1);
            Dir fwd = Dir.toward(-cx, -cz);
            spawnIsland(m, t, r, n, i, new Frame(cx, top, cz, fwd), ir, teams);
        }
        midIsland(m, t, r, n, teams);

        Decor.flora(c, t, r, c.minX(), c.minZ(), c.maxX(), c.maxZ(), TOP - 3, TOP + 2, t.floraDensity);
        m.clearAboveChests();
        m.boundsFromCanvas(25);
        m.meta.put("islands", islands);
        m.meta.put("layout", teams ? "skywars-teams" : "skywars-solo");

        waitingLobby(m, t);
        m.point(MapKeys.SPECTATOR, 0.5, TOP + 25, 0.5, 0f);
        return m;
    }

    // =========================================================================================== spawn island
    private static void spawnIsland(GeneratedMap m, Theme t, Random r, Noise n, int index, Frame fr, double ir, boolean teams) {
        BlockCanvas c = m.canvas;
        int top = fr.oy;
        double sq = 0.25 + r.nextDouble() * 0.45;
        Shapes.island(c, t, n, fr.ox + 0.5, top, fr.oz + 0.5, ir, ir + r.nextDouble() - 0.5, 8 + r.nextInt(3), sq, 0.14, true);

        // a secondary lump so islands are not perfectly symmetric
        Dir lumpDir = r.nextBoolean() ? fr.leftDir() : fr.rightDir();
        Shapes.island(c, t, n, fr.ox + lumpDir.dx * (ir - 1) + 0.5, top - 1, fr.oz + lumpDir.dz * (ir - 1) + 0.5,
                2.8, 2.8, 4, 0.2, 0.2, true);

        int spread = teams ? 4 : 3;
        // cage
        int cs = 0, cf = 2;
        ensureGround(c, t, fr, cs, cf);
        m.point(MapKeys.islandCage(index), fr.x(cs, cf) + 0.5, top + 5, fr.z(cs, cf) + 0.5, fr.fwd().yaw());

        // chests: left, right, back (facing the island centre)
        int[][] chestSpots = {{-spread, -1}, {spread, -1}, {1, -spread - (teams ? 1 : 0)}};
        Dir[] faces = {fr.rightDir(), fr.leftDir(), fr.fwd()};
        for (int k = 0; k < 3; k++) {
            int s = chestSpots[k][0], f = chestSpots[k][1];
            ensureGround(c, t, fr, s, f);
            int x = fr.x(s, f), z = fr.z(s, f);
            c.set(x, top + 1, z, B.s(B.CHEST, faces[k].facing()));
            m.block(MapKeys.islandChests(index), x, top + 1, z);
        }

        // tree at the back corner, props
        int ts = -2, tf = -3 - (teams ? 1 : 0);
        ensureGround(c, t, fr, ts, tf);
        Decor.tree(c, t, r, fr.x(ts, tf), top, fr.z(ts, tf));
        int ps = 3, pf = 2;
        int px = fr.x(ps, pf), pz = fr.z(ps, pf);
        if (c.isGround(px, top, pz) && c.isAir(px, top + 1, pz)) {
            switch (t.id) {
                case "desert": c.set(px, top + 1, pz, B.s(B.SANDSTONE, 1)); c.set(px, top + 2, pz, B.s(B.SLAB, B.SLAB_SANDSTONE)); break;
                case "snow": c.set(px, top + 1, pz, B.s(B.PACKED_ICE)); c.set(px, top + 2, pz, B.s(B.PACKED_ICE)); break;
                default: c.set(px, top + 1, pz, r.nextBoolean() ? B.s(B.HAY) : t.crate); break;
            }
        }
        if (teams) {
            // teams islands get a small hut wall + lamp near the back chest
            int hx = fr.x(-3, -3), hz = fr.z(-3, -3);
            if (c.isGround(hx, top, hz)) Decor.lampPost(c, t, hx, top, hz, 2);
        }

        int e = (int) Math.ceil(ir) + 4;
        m.box(MapKeys.islandBounds(index), fr.ox - e, top - 12, fr.oz - e, fr.ox + e, top + 8, fr.oz + e);
    }

    private static void ensureGround(BlockCanvas c, Theme t, Frame fr, int s, int f) {
        int x = fr.x(s, f), z = fr.z(s, f);
        if (!c.isGround(x, fr.oy, z)) {
            c.set(x, fr.oy, z, t.top);
            Shapes.support(c, x, fr.oy - 1, z, 4, t.sub);
        }
        for (int y = 1; y <= 2; y++) if (!c.isAir(x, fr.oy + y, z)) c.set(x, fr.oy + y, z, 0);
    }

    // =========================================================================================== mid
    private static void midIsland(GeneratedMap m, Theme t, Random r, Noise n, boolean teams) {
        BlockCanvas c = m.canvas;
        double rm = teams ? 12 : 11;
        Shapes.island(c, t, n, 0.5, TOP, 0.5, rm, rm, 14, 0.3, 0.1, true);
        int platY = TOP + 3;
        double platR = 4.5, p = 3;
        for (int x = -6; x <= 6; x++)
            for (int z = -6; z <= 6; z++) {
                if (!Shapes.inside(x, z, platR, p)) continue;
                boolean edge = !Shapes.inside(x, z, platR - 1, p);
                for (int y = TOP + 1; y < platY; y++) c.set(x, y, z, t.wallAlt);
                c.set(x, platY, z, edge ? t.floorTrim : t.floor);
            }
        for (Dir d : Dir.values()) {
            for (int i = 0; i < 3; i++) {
                int k = 5 + i, y = platY - i;
                int x = d.dx * k, z = d.dz * k;
                c.set(x, y, z, t.stairs(d.opposite()));
                for (int yy = TOP + 1; yy < y; yy++) c.set(x, yy, z, t.wallAlt);
            }
        }
        Features.skywarsCentre(c, t, r, 0, platY + 1, 0);
        m.stand(MapKeys.MID, 0, platY + 1, 3, 180f);

        // platform chests
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = sx * 2, z = sz * 2;
                Dir face = sx > 0 ? Dir.EAST : Dir.WEST;
                c.set(x, platY + 1, z, B.s(B.CHEST, face.facing()));
                m.block(MapKeys.MID_CHESTS, x, platY + 1, z);
            }
        // ground chests on the diagonals
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = sx * 6, z = sz * 6;
                if (!c.isGround(x, TOP, z)) { c.set(x, TOP, z, t.top); Shapes.support(c, x, TOP - 1, z, 4, t.sub); }
                c.set(x, TOP + 1, z, B.s(B.CHEST, (sz > 0 ? Dir.NORTH : Dir.SOUTH).facing()));
                m.block(MapKeys.MID_CHESTS, x, TOP + 1, z);
                // a lamp beside each ground chest
                int lx = x + sx, lz = z - sz;
                if (c.isGround(lx, TOP, lz) && c.isAir(lx, TOP + 1, lz)) Decor.lampPost(c, t, lx, TOP, lz, 2);
            }
        // trees on the outer ring between the ramps
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45 + 90 * i + 20);
            int x = (int) Math.round(Math.cos(a) * (rm - 2.5)), z = (int) Math.round(Math.sin(a) * (rm - 2.5));
            int gy = c.surface(x, z, TOP + 1);
            if (gy >= TOP - 1 && c.isAir(x, gy + 1, z)) {
                if (i % 2 == 0) Decor.tree(c, t, r, x, gy, z);
                else Decor.rocks(c, r, x, gy, z, new int[]{t.wallAlt, t.core, t.slab});
            }
        }
    }

    // =========================================================================================== waiting lobby
    private static void waitingLobby(GeneratedMap m, Theme t) {
        BlockCanvas c = m.canvas;
        int wy = m.buildLimitY + 12;
        int half = 7;
        for (int x = -half; x <= half; x++)
            for (int z = -half; z <= half; z++) {
                int ring = Math.max(Math.abs(x), Math.abs(z));
                c.set(x, wy, z, ring == half ? t.floorTrim : ring == 0 ? t.lamp : (ring % 2 == 0 ? t.floor : t.floorAlt));
            }
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
