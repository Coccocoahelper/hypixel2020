package net.h2020.watchdog.framework;

import net.h2020.watchdog.WatchdogServiceImpl;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Lag-compensated geometry inputs shared by fight / block checks. Main thread only.
 * The client attacks with the target positions it rendered (server position ~ping + 3 ticks of interpolation ago)
 * and from its own last sent / interpolated position, so checks take the most lenient combination.
 */
public final class Hitboxes {
    private Hitboxes() {}

    /** 1.8 entity collision border used by the client ray trace (Entity.getCollisionBorderSize). */
    public static final double BORDER = 0.1;

    /** Target hitboxes the attacker could have seen, already expanded by the collision border. */
    public static List<Box> targetBoxes(WatchdogServiceImpl service, Entity victim, Player attacker, long now) {
        List<Box> out = new ArrayList<Box>();
        if (victim instanceof Player) {
            long window = Util.ping(attacker) + 250L;
            for (Box b : service.tracker().boxesSince(victim.getEntityId(), now - window)) out.add(b.expand(BORDER));
        }
        try {
            out.add(Box.of(((CraftEntity) victim).getHandle().getBoundingBox()).expand(BORDER + (victim instanceof Player ? 0 : 0.1)));
        } catch (Throwable t) {
            Location l = victim.getLocation();
            out.add(Box.player(l.getX(), l.getY(), l.getZ()).expand(BORDER));
        }
        return out;
    }

    /** Candidate eye positions: server location, last and previous client-sent position. Caller holds d's lock. */
    public static List<double[]> eyes(Player p, PlayerData d) {
        List<double[]> out = new ArrayList<double[]>(3);
        double eh = Util.eyeHeight(p.isSneaking());
        Location l = p.getLocation();
        out.add(new double[] {l.getX(), l.getY() + eh, l.getZ()});
        if (d.hasPosition) {
            out.add(new double[] {d.x, d.y + eh, d.z});
            out.add(new double[] {d.prevX, d.prevY + eh, d.prevZ});
        }
        return out;
    }

    /** Candidate look vectors: up to n most recent client rotations plus the server's current rotation. */
    public static List<double[]> looks(Player p, PlayerData d, int n) {
        List<double[]> out = new ArrayList<double[]>(n + 1);
        float[] yaw = new float[n], pitch = new float[n];
        int c = d.recentRotations(yaw, pitch, n);
        for (int i = 0; i < c; i++) out.add(Geometry.direction(yaw[i], pitch[i]));
        Location l = p.getLocation();
        out.add(Geometry.direction(l.getYaw(), l.getPitch()));
        return out;
    }
}
