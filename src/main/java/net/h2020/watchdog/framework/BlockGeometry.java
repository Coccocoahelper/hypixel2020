package net.h2020.watchdog.framework;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.List;

/** Reach / direction / face-visibility math for block interactions. Main thread, PlayerData locked. */
public final class BlockGeometry {
    private BlockGeometry() {}

    /** Shortest distance from any plausible eye position to the box. */
    public static double reach(Player p, PlayerData d, Box box) {
        double min = Double.MAX_VALUE;
        for (double[] e : Hitboxes.eyes(p, d)) min = Math.min(min, Geometry.distanceToBox(e[0], e[1], e[2], box));
        return min;
    }

    /** Smallest miss distance of the look ray (recent rotations x eye positions) to the box; 0 = hit. */
    public static double directionOffset(Player p, PlayerData d, Box box) {
        double min = Double.MAX_VALUE;
        List<double[]> eyes = Hitboxes.eyes(p, d);
        List<double[]> looks = Hitboxes.looks(p, d, 3);
        for (double[] e : eyes) {
            for (double[] l : looks) {
                min = Math.min(min, Geometry.rayOffset(e[0], e[1], e[2], l[0], l[1], l[2], box, 7.0));
                if (min <= 0) return 0;
            }
        }
        return min;
    }

    /**
     * Whether a face of block (bx,by,bz) can be seen (and thus ray-traced) from any eye position: the eye must be on
     * the outer side of the face plane (with tolerance for movement between packets).
     */
    public static boolean faceVisible(Player p, PlayerData d, int bx, int by, int bz, BlockFace face, double tol) {
        for (double[] e : Hitboxes.eyes(p, d)) {
            switch (face) {
                case UP: if (e[1] > by + 1 - tol) return true; break;
                case DOWN: if (e[1] < by + tol) return true; break;
                case NORTH: if (e[2] < bz + tol) return true; break;
                case SOUTH: if (e[2] > bz + 1 - tol) return true; break;
                case WEST: if (e[0] < bx + tol) return true; break;
                case EAST: if (e[0] > bx + 1 - tol) return true; break;
                default: return true;
            }
        }
        return false;
    }

    /** 1.8 C08 face index -> BlockFace (0 down, 1 up, 2 north, 3 south, 4 west, 5 east). */
    public static BlockFace face(int id) {
        switch (id) {
            case 0: return BlockFace.DOWN;
            case 1: return BlockFace.UP;
            case 2: return BlockFace.NORTH;
            case 3: return BlockFace.SOUTH;
            case 4: return BlockFace.WEST;
            case 5: return BlockFace.EAST;
            default: return BlockFace.SELF;
        }
    }
}
