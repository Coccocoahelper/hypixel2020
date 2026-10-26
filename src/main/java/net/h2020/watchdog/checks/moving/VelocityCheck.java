package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;
import net.minecraft.server.v1_8_R3.AxisAlignedBB;

/**
 * Anti-knockback ("Velocity"). S12 replaces the client's motion, so on the tick the client applies it the move is
 * {@code v + a} where {@code a} is that tick's input acceleration (|a| <= ground sprint accel; a sprint-hit in the
 * same tick multiplies the motion by 0.6 first). Within a latency window after each knockback the check looks for
 * such a tick horizontally and for the vertical take-off (dy >= min(vy, jump) unless a ceiling blocks it).
 * Knockback into a wall, in liquids/webs/ladders, while sneaking on an edge, combined with explosions or across
 * teleports is not judged. Flags when 3 of the last 8 judged hits were ignored.
 */
final class VelocityCheck {
    private VelocityCheck() {}

    static void check(MovingEngine eng, MoveContext c) {
        MovingData d = c.data;
        if (eng.config.legacyVelocityGap) { d.kbEntry = null; return; }   // 2020 blind spot: 0/0 velocity went through
        VelocityTracker.Entry nv = d.velocity.newestSet(d.kbLastId);
        if (nv != null) {
            d.kbLastId = nv.id;
            if (nv.h >= 0.2 || nv.y >= 0.25) {
                d.kbEntry = nv;
                d.kbWindowEnd = nv.seq + 8 + 2L * (c.ping / 50);
                d.kbBestErr = Double.MAX_VALUE;
                d.kbBestRatio = 0;
                d.kbTookV = false;
                d.kbInvalidH = nv.h < 0.2;
                d.kbInvalidV = nv.y < 0.25;
                d.kbSawMove = false;
            } else {
                d.kbEntry = null;
            }
        }
        VelocityTracker.Entry v = d.kbEntry;
        if (v == null) return;
        long seq = c.entry.seq;
        if (seq <= v.seq) return;
        if (seq > d.kbWindowEnd) {
            judge(eng, c);
            d.kbEntry = null;
            return;
        }
        if (c.flyingMode || c.creativeLike || c.from.liquidLoose || c.to.liquidLoose || c.from.web || c.to.web
                || c.from.climbable || c.to.climbable || !c.from.loaded || d.leniencyTicks > 0
                || d.velocity.hasAdditiveSince(v.seq)) {
            d.kbInvalidH = true;
            d.kbInvalidV = true;
            return;
        }
        if (!c.hasPos) return;
        d.kbSawMove = true;

        if (!d.kbInvalidH) {
            if (c.sneaking && c.from.ground) {
                d.kbInvalidH = true;
            } else {
                AxisAlignedBB pushed = Collisions.box(c.fromX, c.fromY, c.fromZ).shrink(0.001, 0.001, 0.001).c(v.x, 0, v.z);
                if (Collisions.collides(c.world, c.handle, pushed, d.ghosts, c.entry.time, true)) {
                    d.kbInvalidH = true;
                } else {
                    double tol = c.horizontalAccel() + c.push + 0.04 + (d.unreported ? SurvivalFly.UNREPORTED : 0)
                            + (c.from.ground && c.dy > 1.0E-4 && c.sprinting ? 0.2 : 0); // sprint-jump in that tick
                    double err = Math.hypot(c.dx - v.x, c.dz - v.z) - tol;
                    if (c.attacked) {
                        err = Math.min(err, Math.hypot(c.dx - v.x * 0.6, c.dz - v.z * 0.6) - tol);
                        err = Math.min(err, Math.hypot(c.dx - v.x * 0.36, c.dz - v.z * 0.36) - tol);
                    }
                    d.kbBestErr = Math.min(d.kbBestErr, err);
                    double ratio = (c.dx * v.x + c.dz * v.z) / (v.h * v.h);
                    d.kbBestRatio = Math.max(d.kbBestRatio, ratio);
                }
            }
        }
        if (!d.kbInvalidV) {
            if (c.from.headBlocked || c.to.headBlocked) {
                d.kbInvalidV = true;
            } else if (c.dy >= Math.min(v.y, c.jumpMotion) * 0.95 - 0.01) {
                d.kbTookV = true;
            }
        }
    }

    private static void judge(MovingEngine eng, MoveContext c) {
        MovingData d = c.data;
        if (!d.kbSawMove || (d.kbInvalidH && d.kbInvalidV)) return;
        boolean failH = !d.kbInvalidH && d.kbBestErr > 0;
        boolean failV = !d.kbInvalidV && !d.kbTookV;
        boolean fail = failH || failV;
        d.kbHistory[d.kbHistoryIdx] = fail;
        d.kbHistoryIdx = (d.kbHistoryIdx + 1) % d.kbHistory.length;
        if (d.kbHistoryCount < d.kbHistory.length) d.kbHistoryCount++;
        if (!fail || !eng.active(c.player, CheckType.MOVING_VELOCITY)) return;
        int fails = 0;
        for (int i = 0; i < d.kbHistoryCount; i++) if (d.kbHistory[i]) fails++;
        if (fails < 3) return;
        VelocityTracker.Entry v = d.kbEntry;
        String details = String.format("velocity: took %.0f%% horizontal%s of knockback %.3f/%.3f (%d of last %d hits, ping %dms)",
                Math.max(0, Math.min(1, d.kbBestRatio)) * 100.0, failV ? ", no vertical" : "",
                v.h, v.y, fails, d.kbHistoryCount, c.ping);
        eng.flag(c.player, CheckType.MOVING_VELOCITY, 1.0 + (fails - 3) * 0.5, details);
    }
}
