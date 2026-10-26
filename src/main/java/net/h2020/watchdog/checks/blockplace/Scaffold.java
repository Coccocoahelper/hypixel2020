package net.h2020.watchdog.checks.blockplace;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.Geometry;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Watchdog scaffold heuristics for blocks placed under the player's feet:
 * <ul>
 * <li><b>pitch</b>: the block below can only be ray-traced at >= ~45 degrees pitch; placing it while every recent
 *   rotation looked flatter than 35 degrees is impossible.</li>
 * <li><b>snap</b>: silent-rotation scaffolds flick the yaw 75+ degrees for the placement and snap back within 3
 *   ticks; three such snaps within 5s.</li>
 * <li><b>sprint</b>: sprinting forward (moving the way you look) at a flat-ish pitch while placing below behind you,
 *   5+ times within 2s. Shift/speed/ninja/breezily bridging moves backwards or diagonally-backwards and
 *   cannot sprint, so it never matches.</li>
 * </ul>
 */
public final class Scaffold extends Check {
    public Scaffold(WatchdogServiceImpl service) { super(service, CheckType.BLOCKPLACE_SCAFFOLD); }

    /** Main thread: BlockPlaceEvent. @return cancel */
    public boolean check(Player p, PlayerData d, Block placed, long now) {
        if (exempt(p) || Util.creativeLike(p) || p.isFlying()) return false;
        Location l = p.getLocation();
        double feetY = l.getY();
        int py = placed.getY();
        if (!(py < Math.floor(feetY + 0.001) && py >= Math.floor(feetY) - 2)) return false;
        double dx = placed.getX() + 0.5 - l.getX(), dz = placed.getZ() + 0.5 - l.getZ();
        if (dx * dx + dz * dz > 1.6 * 1.6) return false;

        float maxPitch = Math.max(l.getPitch(), Math.max(d.pitchAgo(0), Math.max(d.pitchAgo(1), d.pitchAgo(2))));
        BlockPlaceData pd = d.place;
        boolean cancel = false;

        // 1) impossible pitch for a block below the feet
        if (maxPitch < 35f) cancel = flag(p, 3.0, "pitch=" + Util.fmt1(maxPitch) + " below feet");

        // 3) sprinting forward while placing under/behind
        if (d.sprinting && d.deltaXZ > 0.2 && maxPitch < 70f) {
            double mx = (d.x - d.prevX) / d.deltaXZ, mz = (d.z - d.prevZ) / d.deltaXZ;
            double[] look = Geometry.direction(d.yaw, 0f);
            double dot = mx * look[0] + mz * look[2];
            double behind = (dx * mx + dz * mz);
            if (dot > 0.6 && behind < 0.3) {
                pd.sprintPlaceTimes[pd.sprintIdx] = now;
                pd.sprintIdx = (pd.sprintIdx + 1) % pd.sprintPlaceTimes.length;
                int recent = 0;
                for (long t : pd.sprintPlaceTimes) if (now - t < 2000) recent++;
                if (recent >= 5 && flag(p, 2.0, "sprint-place recent=" + recent + " pitch=" + Util.fmt1(maxPitch))) cancel = true;
            }
        }
        if (!cancel && debugging(p)) debug(p, "ok below pitch=" + Util.fmt1(maxPitch) + " sprint=" + d.sprinting);
        return cancel;
    }

    /**
     * Packet thread, block-placing C08 (exact packet order, so d.yaw is the rotation the placement was sent with).
     * Starts tracking a yaw snap into the placement; onTickEnd confirms the snap back.
     */
    public void onPlacePacket(PlayerData d, int placedX, int placedY, int placedZ) {
        if (!d.hasPosition) return;
        if (!(placedY < Math.floor(d.y + 0.001) && placedY >= Math.floor(d.y) - 2)) return;
        double dx = placedX + 0.5 - d.x, dz = placedZ + 0.5 - d.z;
        if (dx * dx + dz * dz > 1.6 * 1.6) return;
        float before = d.yawAgo(2), at = d.yaw;
        if (Geometry.yawDiff(at, before) > 75f) {
            BlockPlaceData pd = d.place;
            pd.snapPending = true;
            pd.snapYawAtPlace = at;
            pd.snapYawBefore = before;
            pd.snapTicksLeft = 3;
        }
    }

    /** Packet thread, end of a client tick: confirms yaw snaps back after a placement. */
    public void onTickEnd(Player p, PlayerData d, long now) {
        BlockPlaceData pd = d.place;
        if (!pd.snapPending) return;
        if (Geometry.yawDiff(d.yaw, pd.snapYawAtPlace) > 75f && Geometry.yawDiff(d.yaw, pd.snapYawBefore) < 35f) {
            pd.snapPending = false;
            pd.snapTimes[pd.snapIdx] = now;
            pd.snapIdx = (pd.snapIdx + 1) % pd.snapTimes.length;
            int recent = 0;
            for (long t : pd.snapTimes) if (now - t < 5000) recent++;
            if (recent >= 3 && !exempt(p)) flag(p, 4.0, "rotation snaps=" + recent + " in 5s");
            else debug(p, "snap " + recent + "/3");
            return;
        }
        if (--pd.snapTicksLeft <= 0) pd.snapPending = false;
    }
}
