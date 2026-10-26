package net.h2020.bots.ai.perception;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Memory of one other player: detection meter, last known state, threat. */
public final class Sighting {
    public final Player player;
    /** Detection meter 0..1+; crossing 1 (after reaction time) makes the player "known". */
    public double meter;
    public boolean known;
    public long noticeAt = -1;
    /** In the vision cone with clear line of sight on the latest scan. */
    public boolean visible;
    public Location lastPos;
    public Vector lastVel = new Vector();
    public long lastSeenTick = -10000;
    public long lastHeardTick = -10000;
    public long lastHurtMeTick = -10000;
    public long lastHitByMeTick = -10000;
    public int hitsOnMe;
    public boolean ally;
    public double threat;
    public double opportunity;
    /** Raw position on the previous scan (seen or not) for footstep loudness. */
    Location scanPos;
    long scanTick;
    double scanSpeed;

    Sighting(Player p) { this.player = p; }

    /** Seconds since last direct sight. */
    public double unseenSeconds(long now) { return (now - lastSeenTick) / 20.0; }

    /** 1 while visible, decaying to 0 over ~15s of not seeing/hearing them. */
    public double confidence(long now) {
        long last = Math.max(lastSeenTick, Math.max(lastHeardTick, lastHurtMeTick));
        double dt = (now - last) / 20.0;
        if (dt <= 0) return 1;
        return Math.exp(-dt / 7.0);
    }

    /** Where we think they are now (extrapolated briefly from last velocity). */
    public Location estimate(long now) {
        if (lastPos == null) return null;
        double dt = Math.min(30, now - lastSeenTick);
        return lastPos.clone().add(lastVel.clone().multiply(dt * 0.6));
    }
}
