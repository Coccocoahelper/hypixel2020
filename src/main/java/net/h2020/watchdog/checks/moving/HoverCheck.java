package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * SurvivalFly hover (NCP sfHoverTicks): run every server tick. A player whose server position does not change
 * while it is in the air (no ground, liquid, climbable or web under the box, ghost blocks included) for more than
 * 20 client ticks is hovering. Counting client ticks (flying packets) instead of server ticks means a lagging
 * client is never judged for ticks it did not send.
 */
final class HoverCheck {
    static final int MAX_TICKS = 20;

    private HoverCheck() {}

    static void tick(MovingEngine eng, Player p, EntityPlayer h, WorldServer w, MovingData d, long now) {
        if (p.getAllowFlight() || p.isFlying() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR
                || p.isInsideVehicle() || p.isDead() || d.awaitTeleport || d.leniencyTicks > 0 || now < d.flyGraceUntil
                || !eng.active(p, CheckType.MOVING_SURVIVALFLY)) {
            d.hoverTicks = 0;
            d.hoverSeq = d.inSeq;
            d.hoverX = h.locX; d.hoverY = h.locY; d.hoverZ = h.locZ;
            return;
        }
        double x = h.locX, y = h.locY, z = h.locZ;
        if (Math.abs(x - d.hoverX) > 1.0E-4 || Math.abs(y - d.hoverY) > 1.0E-4 || Math.abs(z - d.hoverZ) > 1.0E-4) {
            d.hoverX = x; d.hoverY = y; d.hoverZ = z;
            d.hoverTicks = 0;
            d.hoverSeq = d.inSeq;
            return;
        }
        long packets = d.inSeq - d.hoverSeq;
        d.hoverSeq = d.inSeq;
        if (packets <= 0) return;
        PosInfo info = Collisions.info(w, h, x, y, z, d.ghosts, now);
        if (!info.loaded || info.ground || info.liquidLoose || info.climbable || info.web
                || (d.prevClientGround && Collisions.groundWithin(w, h, x, y, z, SurvivalFly.UNREPORTED, d.ghosts, now))) {
            d.hoverTicks = 0;
            return;
        }
        d.hoverTicks += (int) Math.min(packets, 40);
        if (d.hoverTicks > MAX_TICKS) {
            int ticks = d.hoverTicks;
            d.hoverTicks = 0;
            boolean cancel = eng.flag(p, CheckType.MOVING_SURVIVALFLY, 5.0,
                    "survivalfly: tags hover (" + ticks + " ticks in air without moving, ping " + d.pingSnapshot + "ms)");
            if (cancel) eng.requestSetback(p, d);
        }
    }
}
