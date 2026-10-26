package net.h2020.maps.gen;

import net.h2020.api.game.GameFamily;
import org.bukkit.block.Biome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Output of a map generator: the painted canvas plus everything that goes into map.yml. */
public final class GeneratedMap {
    public final String name;
    public final GameFamily family;
    public final List<Integer> teamCounts = new ArrayList<Integer>();
    public final BlockCanvas canvas = new BlockCanvas();
    /** key -> list of {x,y,z,yaw}. */
    public final Map<String, List<double[]>> points = new LinkedHashMap<String, List<double[]>>();
    public final Map<String, Object> meta = new LinkedHashMap<String, Object>();
    public Biome biome = Biome.PLAINS;
    public String theme = "";
    public int buildLimitY = 255;
    public int[] boundsMin = {-256, 0, -256};
    public int[] boundsMax = {256, 255, 256};
    /** World spawn (x,y,z,yaw); defaults to the waiting lobby. */
    public double[] spawn = {0.5, 100, 0.5, 0};

    public GeneratedMap(String name, GameFamily family) { this.name = name; this.family = family; }

    /** Exact point (fractional coordinates allowed). */
    public void point(String key, double x, double y, double z, float yaw) {
        List<double[]> l = points.get(key);
        if (l == null) { l = new ArrayList<double[]>(); points.put(key, l); }
        l.add(new double[]{x, y, z, yaw});
    }

    /** Standing position on a block: centred x/z (+0.5), feet y. */
    public void stand(String key, int x, int y, int z, float yaw) { point(key, x + 0.5, y, z + 0.5, yaw); }

    /** Block coordinate point (beds, chests, bounds corners). */
    public void block(String key, int x, int y, int z) { point(key, x, y, z, 0f); }

    /** Two-point box key (min then max) from any two corners. */
    public void box(String key, int x1, int y1, int z1, int x2, int y2, int z2) {
        block(key, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
        block(key, Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Chests cannot be opened under an opaque block: clear whatever decoration ended up above any chest point. */
    public void clearAboveChests() {
        for (Map.Entry<String, List<double[]>> e : points.entrySet()) {
            if (!e.getKey().endsWith("chests")) continue;
            for (double[] p : e.getValue()) {
                int x = (int) Math.floor(p[0]), y = (int) Math.floor(p[1]), z = (int) Math.floor(p[2]);
                if (!canvas.isAir(x, y + 1, z)) canvas.set(x, y + 1, z, 0);
            }
        }
    }

    /**
     * Set map bounds from the current canvas extents plus a horizontal margin (y from 0 to the build limit).
     * Call before adding the waiting lobby so the floating box does not widen the play area.
     */
    public void boundsFromCanvas(int margin) {
        BlockCanvas c = canvas;
        boundsMin = new int[]{c.minX() - margin, 0, c.minZ() - margin};
        boundsMax = new int[]{c.maxX() + margin, buildLimitY, c.maxZ() + margin};
    }
}
