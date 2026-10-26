package net.h2020.watchdog.checks.moving;

import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/** Everything the moving checks need to judge one client tick. */
final class MoveContext {
    Player player;
    EntityPlayer handle;
    WorldServer world;
    MovingData data;
    FlyingEntry entry;
    /** Evaluated inside this packet's PlayerMoveEvent (the server has not applied it yet). */
    boolean live;
    long now;
    int ping;

    double fromX, fromY, fromZ, toX, toY, toZ;
    double dx, dy, dz, hDist;
    boolean hasPos;
    PosInfo from, to;

    // movement modifiers (server state with grace for client latency)
    boolean sprinting, sneaking, blocking;
    boolean creativeLike, spectator, flyingMode;
    /** Client attacked an entity during this tick (sprint-hit slows motion by 0.6). */
    boolean attacked;
    double moveSpeed;
    double inputMag;
    int jumpAmp;
    double jumpMotion;
    double push;
    /** Move matches the 2020 hover-fly pattern and the legacy gap is enabled: SurvivalFly/Passable let it through. */
    boolean legacyHover;
    int depthStrider;
    List<VelocityTracker.Entry> velocities;

    /** A check found a violation in this tick (the position is not used as set-back). */
    boolean violated;

    /** Set-back requested by a check (first request wins). */
    Location setback;

    void requestSetback(Location loc) {
        if (setback == null && loc != null) setback = loc;
    }

    Location fromLocation() {
        return new Location(player.getWorld(), fromX, fromY, fromZ);
    }

    /** Ground acceleration of the from-position (moveSpeed * 0.16277136 / f^3), or air acceleration. */
    double horizontalAccel() {
        if (from.ground) {
            double f4 = from.slip * 0.91;
            return moveSpeed * (0.16277136 / (f4 * f4 * f4)) * inputMag;
        }
        return (sprinting ? 0.026 : 0.02) * inputMag;
    }
}
