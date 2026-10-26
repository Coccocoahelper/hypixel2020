package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;

import java.util.ArrayList;
import java.util.List;

/**
 * NCP SurvivalFly, rebuilt on the exact 1.8.9 client movement (EntityLivingBase.moveEntityWithHeading):
 * <pre>
 *   f4    = onGround ? slipperiness(block below) * 0.91 : 0.91
 *   accel = onGround ? moveSpeed * 0.16277136 / f4^3 : jumpMovementFactor (0.02, sprinting 0.026)
 *   motion += accel * input          (moveFlying; sneak 0.3, using item 0.2)
 *   jump: motionY = 0.42 (+0.1/level), sprinting adds 0.2 towards the yaw
 *   move -> motionXZ *= f4(post move), motionY = (motionY - 0.08) * 0.98
 *   water: motion *= 0.8, motionY -= 0.02 (+0.04 swimming up, 0.3 edge hop); lava 0.5; web: x0.25 / y0.05, motion
 *   reset; ladder: motionY >= -0.15, climbing 0.2; soul sand x0.4; slime bounce.
 * </pre>
 * The check keeps upper bounds of the motion carried into the next tick (hDistRel / vDistRel) and compares every
 * client tick against them, with NCP velocity entries for knockback/explosions, a small horizontal buffer and the
 * vanilla rule that a tick without a position update moved less than 0.03.
 * Steady walk 0.2203/tick (0.2158 straight), sprint 0.2863 (0.2806 straight) and the sprint-jump tick
 * (0.2863 * 0.546 + 0.13 + 0.2 = 0.4863) fall out of the model rather than being constants.
 */
final class SurvivalFly {
    static final double GRAVITY = 0.08;
    static final double DRAG = 0.98;
    /** Motion after a tick standing on the ground: (0 - 0.08) * 0.98. */
    static final double GROUND_MOTION = (0.0 - GRAVITY) * DRAG;
    /** Ladder climb: motionY = 0.2 after a horizontal collision, then gravity. */
    static final double CLIMB_UP = (0.2 - GRAVITY) * DRAG;
    static final double STEP = 0.6;
    /** EntityPlayerSP sends a position only after moving more than 0.03 (sqrt 9.0E-4). */
    static final double UNREPORTED = 0.03;

    private SurvivalFly() {}

    /**
     * Optional 2020 blind spot (watchdog.movement.legacy-2020-bhop-gap): NCP's bunnyhop model, which Hypixel's 2020
     * Watchdog effectively accepted. A real jump may carry up to 2.15x sprint speed, and for the rest of that jump each
     * air tick may keep all but 1/160 of the previous distance. Anything faster is still checked normally.
     * Returns 0 when the gap doesn't apply.
     */
    private static double legacyBunny(MovingEngine eng, MoveContext c, MovingData d, boolean jumped, boolean special, double extra) {
        if (!eng.config.legacyBhopGap || !c.hasPos || special) { d.bunnyTicks = 0; return 0; }
        double sprintBase = 0.2873 * c.moveSpeed / (c.sprinting ? 0.13 : 0.1);   // ground sprint distance incl. Speed potion
        if (jumped && c.dy >= c.jumpMotion - 0.01) {
            d.bunnyTicks = 12;
            return (sprintBase * 2.15 + extra) * 1.003;
        }
        if (d.bunnyTicks > 0 && !c.from.ground) {
            d.bunnyTicks--;
            return Math.max(sprintBase, d.lastHDist - d.lastHDist / 160.0) * 1.003 + extra + 0.0015;
        }
        if (c.from.ground) d.bunnyTicks = 0;
        return 0;
    }

    static void check(MovingEngine eng, MoveContext c) {
        MovingData d = c.data;
        PosInfo f = c.from, t = c.to;
        boolean active = eng.active(c.player, CheckType.MOVING_SURVIVALFLY) && d.leniencyTicks == 0 && f.loaded && t.loaded
                && !c.legacyHover; // 2020 blind spot (config), see MoveProcessor.legacyHoverPattern
        List<String> tags = new ArrayList<>(2);
        StringBuilder info = new StringBuilder();
        double vl = 0;

        // ------------------------------------------------------------------ horizontal (hDistRel)
        boolean water = f.liquid == 1, lava = f.liquid == 2;
        double liquidFriction = 0.91;
        double accel;
        if (water) {
            double ds = Math.min(3, c.depthStrider);
            if (!f.ground) ds *= 0.5;
            liquidFriction = 0.8 + (0.54600006 - 0.8) * ds / 3.0;
            accel = (0.02 + (c.moveSpeed - 0.02) * ds / 3.0) * c.inputMag;
        } else if (lava) {
            liquidFriction = 0.5;
            accel = 0.02 * c.inputMag;
        } else {
            accel = c.horizontalAccel();
        }
        boolean jumped = f.ground && !water && !lava && c.hasPos && c.dy > 1.0E-4;
        double jumpBoost = jumped && c.sprinting ? 0.2 : 0.0;
        double extra = c.push + (f.liquidLoose || t.liquidLoose ? 0.03 : 0.0) + (d.unreported ? UNREPORTED : 0.0);

        double hNormal = d.nextHBase + accel + jumpBoost;
        if (f.web) hNormal *= 0.25;
        double hSet = 0, hAdd = 0;
        VelocityTracker.Entry setEntry = null;
        for (VelocityTracker.Entry v : c.velocities) {
            if (v.additive) {
                hAdd += v.h;
            } else {
                double cand = v.h + accel + jumpBoost;
                if (f.web) cand *= 0.25;
                if (cand > hSet) { hSet = cand; setEntry = v; }
            }
        }
        double hBase = Math.max(hNormal, hSet);
        double hLimit = (hBase + hAdd + extra) * 1.003 + 0.0015;
        if ((water || lava) && hLimit < 0.12 + extra) hLimit = 0.12 + extra;
        hLimit = Math.max(hLimit, legacyBunny(eng, c, d, jumped, water || lava || f.web || f.climbable, extra));

        if (c.hasPos && c.legacyHover && eng.active(c.player, CheckType.MOVING_SURVIVALFLY)) {
            // the 2020 gap only covered the hover rhythm at ~2.15x sprint speed, not arbitrary speed
            double legacyCap = (0.2873 * 2.2 + c.push) * 1.01;
            if (c.hDist > legacyCap) {
                vl += (c.hDist - legacyCap) * 100.0;
                tags.add("hspeed");
                info.append(String.format(" hDist %.4f > %.4f (legacy hover cap)", c.hDist, legacyCap));
            }
        }
        if (c.hasPos) {
            if (active && c.hDist > hLimit) {
                double excess = c.hDist - hLimit;
                if (excess <= d.hBuffer) {
                    d.hBuffer -= excess;
                } else {
                    vl += (excess - d.hBuffer) * 100.0;
                    d.hBuffer = 0;
                    tags.add("hspeed");
                    info.append(String.format(" hDist %.4f > %.4f", c.hDist, hLimit));
                }
            } else {
                d.hBuffer = Math.min(MovingData.HBUF_MAX, d.hBuffer + 0.002);
            }
            double noVelocity = (hNormal + extra) * 1.003 + 0.0015;
            if (c.hDist > noVelocity) {
                if (setEntry != null && hSet > hNormal) d.velocity.consume(setEntry, c.entry.seq);
                if (hAdd > 0 && c.hDist > (hBase + extra) * 1.003 + 0.0015) {
                    for (VelocityTracker.Entry v : c.velocities) if (v.additive) d.velocity.consume(v, c.entry.seq);
                }
            }
        }

        double motion = c.hasPos ? c.hDist : Math.min(hBase + hAdd, UNREPORTED);
        if (c.hasPos && c.sneaking && f.ground) motion = Math.max(motion, Math.min(hBase, 0.12)); // sneak edge clamp keeps motion
        if (f.web) motion = 0;                    // Entity.moveEntity zeroes motion inside webs
        if (t.soulSand) motion *= 0.4;            // BlockSoulSand.onEntityCollidedWithBlock
        // Vanilla computes this friction before moveEntity, i.e. from the ground state at the start of the tick
        // (a landing tick still uses air friction 0.91).
        double frictionAfter = (water || lava) ? liquidFriction : (f.ground ? f.slip * 0.91 : 0.91);
        d.nextHBase = motion * frictionAfter;

        // ------------------------------------------------------------------ vertical (vDistRel)
        double hi = d.nextVHigh, lo = d.nextVLow;
        if (lo < 0.005 && hi > -0.005) { hi = Math.max(hi, 0); lo = Math.min(lo, 0); } // |motionY| < 0.005 -> 0
        double setHi = Double.NEGATIVE_INFINITY, setLo = Double.POSITIVE_INFINITY, addHi = 0, addLo = 0;
        VelocityTracker.Entry vSet = null;
        for (VelocityTracker.Entry v : c.velocities) {
            if (v.additive) {
                if (v.y > 0) addHi += v.y; else addLo += v.y;
            } else {
                if (v.y > setHi) { setHi = v.y; vSet = v; }
                setLo = Math.min(setLo, v.y);
            }
        }
        boolean hasSet = vSet != null;
        double upper, lower;
        if (f.web) {
            double a = f.ground ? Math.max(hi, c.jumpMotion) : hi, b = lo;
            if (hasSet) { a = Math.max(a, setHi); b = Math.min(b, setLo); }
            upper = a * 0.05;
            lower = b * 0.05;
        } else if (water || lava) {
            double a = hi, b = lo;
            if (hasSet) { a = Math.max(a, setHi); b = Math.min(b, setLo); }
            upper = a + 0.04;
            lower = b - 0.04;
        } else {
            upper = hi;
            lower = lo;
            if (hasSet) { upper = Math.max(upper, setHi); lower = Math.min(lower, setLo); }
            if (f.ground) upper = Math.max(upper, c.jumpMotion);
            if (f.climbable) {
                upper = Math.max(upper, CLIMB_UP);
                lower = Math.max(lower, -0.15);
                if (c.sneaking) upper = Math.max(upper, 0);
            }
        }
        upper += addHi;
        lower += addLo;
        double upperRaw = upper;
        if (d.unreported) { upper += UNREPORTED; lower -= UNREPORTED; }
        double upperTol = upper + 0.001;
        double lowerTol = lower - 0.001;

        boolean landedUnreported = false;
        if (c.hasPos) {
            double dy = c.dy;
            boolean ok = dy <= upperTol;
            if (!ok && t.ground && dy <= 0) ok = true;                                   // collision clipped a fall
            // Step up: from the ground, or in the tick a fall is stopped by the ground (moveEntity flag1).
            if (!ok && t.ground && dy <= STEP + 1.0E-4 && (f.ground || (hi < 0 && f.nearGround))) ok = true;
            if (!ok && (f.liquidLoose || t.liquidLoose) && dy <= Math.max(hi, 0.3) + 0.04 + addHi) ok = true; // surface
            if (!ok && active) {
                double excess = dy - upperTol;
                if (excess < 0.02 && excess <= d.vBuffer) {
                    d.vBuffer -= excess;
                } else {
                    vl += excess * 100.0;
                    tags.add(f.ground && dy > c.jumpMotion ? "step" : "vdistrel");
                    info.append(String.format(" dY %.4f > %.4f", dy, upperTol));
                }
            } else if (ok) {
                d.vBuffer = Math.min(MovingData.VBUF_MAX, d.vBuffer + 0.0005);
            }
            boolean lowerCheck = !f.ground && !f.liquidLoose && !t.liquidLoose && !f.web && !t.web
                    && !f.climbable && !t.climbable && !t.headBlocked && !f.headBlocked;
            if (active && lowerCheck && dy < lowerTol - 0.01) {
                double excess = lowerTol - dy;
                vl += excess * 100.0;
                tags.add("fastfall");
                info.append(String.format(" dY %.4f < %.4f", dy, lowerTol));
            }
            if (hasSet && dy > hi + addHi + 0.001 && !(f.ground && dy <= c.jumpMotion + 0.001)) d.velocity.consume(vSet, c.entry.seq);
            if (addHi > 0 && dy > upper - addHi + 0.001) {
                for (VelocityTracker.Entry v : c.velocities) if (v.additive) d.velocity.consume(v, c.entry.seq);
            }
        } else if (!f.ground && !f.climbable && !f.liquidLoose && !f.web && !f.headBlocked) {
            if (c.entry.ground && Collisions.groundWithin(c.world, c.handle, f.x, f.y, f.z, UNREPORTED, d.ghosts, c.entry.time)) {
                // Landed less than 0.03 below the last reported position, so the client sent no update.
                landedUnreported = true;
            } else if (active && upperRaw < -2 * UNREPORTED - 0.001) {
                // No position update although gravity must have moved the player at least 0.06 this tick.
                double excess = -2 * UNREPORTED - upperRaw;
                vl += excess * 100.0;
                tags.add("hover");
                info.append(String.format(" no position update, expected dY <= %.4f", upperRaw));
            }
        }

        double adyHi, adyLo;
        if (c.hasPos) {
            adyHi = adyLo = c.dy;
        } else {
            adyHi = Math.min(upper, UNREPORTED);
            adyLo = Math.max(lower, -UNREPORTED);
            if (adyHi < adyLo) adyHi = adyLo = 0;
        }
        double nHi, nLo;
        if (f.web) {
            nHi = nLo = GROUND_MOTION;
        } else if (water || lava) {
            double m = water ? 0.8 : 0.5;
            nHi = adyHi * m - 0.02;
            nLo = adyLo * m - 0.02;
            if (t.ground || t.headBlocked) { nHi = Math.max(nHi, -0.02); nLo = Math.min(nLo, -0.02); }
            nHi = Math.max(nHi, 0.3); // EntityLivingBase: collided horizontally at the surface -> motionY = 0.3
        } else if (t.ground || landedUnreported) {
            nHi = nLo = GROUND_MOTION;
            if (t.onSlime && !c.sneaking && adyLo < 0) nHi = Math.max(nHi, (-lower - GRAVITY) * DRAG); // BlockSlime bounce
        } else {
            nHi = (adyHi - GRAVITY) * DRAG;
            nLo = (adyLo - GRAVITY) * DRAG;
            if (t.headBlocked) nLo = Math.min(nLo, GROUND_MOTION);
        }
        if (t.climbable || f.climbable) nHi = Math.max(nHi, CLIMB_UP);
        if ((f.liquidLoose || t.liquidLoose) && !water && !lava) {
            nHi = Math.max(nHi, 0.3);
            nLo = Math.min(nLo, adyLo * 0.8 - 0.06);
        }
        d.nextVHigh = nHi;
        d.nextVLow = nLo;

        if (vl > 0) {
            c.violated = true;
            String details = "survivalfly: tags " + join(tags) + info + mode(c);
            boolean cancel = eng.flag(c.player, CheckType.MOVING_SURVIVALFLY, Math.min(vl, 50.0), details);
            if (cancel) c.requestSetback(d.setBack != null ? d.setBack : c.fromLocation());
        }
    }

    static String mode(MoveContext c) {
        StringBuilder sb = new StringBuilder(" (");
        sb.append(c.from.ground ? "ground" : "air").append("->").append(c.to.ground ? "ground" : "air");
        if (c.sprinting) sb.append(", sprint");
        if (c.sneaking) sb.append(", sneak");
        if (c.blocking) sb.append(", blocking");
        if (c.from.liquid == 1) sb.append(", water");
        if (c.from.liquid == 2) sb.append(", lava");
        if (c.from.web) sb.append(", web");
        if (c.from.climbable) sb.append(", climbable");
        if (!c.velocities.isEmpty()) sb.append(", velocity x").append(c.velocities.size());
        sb.append(", ping ").append(c.ping).append("ms)");
        return sb.toString();
    }

    static String join(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (String s : tags) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        return sb.toString();
    }
}
