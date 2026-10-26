package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.BlockGeometry;
import net.h2020.watchdog.framework.Box;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * NCP BlockPlace.Direction plus a face test: the look ray must hit the clicked block, and the clicked face must face
 * the eye (you cannot click the far side of a block). Uses recent rotations and positions so fast human bridging
 * with real rotations passes.
 */
public final class Direction extends Check {
    static final double PRECISION = 0.25;
    static final double FACE_TOLERANCE = 0.35;

    public Direction(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_DIRECTION); }

    public boolean check(Player p, PlayerData d, Block clicked, BlockFace face) {
        if (exempt(p)) return false;
        double off = BlockGeometry.directionOffset(p, d, Box.block(clicked.getX(), clicked.getY(), clicked.getZ()));
        boolean visible = face == null || BlockGeometry.faceVisible(p, d, clicked.getX(), clicked.getY(), clicked.getZ(), face, FACE_TOLERANCE);
        if (off > PRECISION || !visible) {
            double vl = Math.min(off, 4.0) * 2.0 + (visible ? 0 : 2.0);
            return flag(p, vl, "off=" + Util.fmt(off) + (visible ? "" : " face=" + face.name().toLowerCase() + "(hidden)"));
        }
        reward(p, 0.97);
        return false;
    }
}
