package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;

/**
 * NCP CreativeFly: players that fly (creative/spectator, allowFlight + flying, Bed Wars spectators) or may still
 * be flying client-side (grace after flight was allowed). 1.8.9 flight (EntityPlayer.moveEntityWithHeading):
 * jumpMovementFactor = flySpeed * (sprinting ? 2 : 1), air friction 0.91, motionY = (motionY +- flySpeed*3) * 0.6.
 * Default flySpeed 0.05 gives 1.11 blocks/tick sprint-flying and 0.375 blocks/tick vertical; anything clearly
 * beyond that (fly speed hacks) is a violation.
 */
final class CreativeFly {
    private CreativeFly() {}

    static void check(MovingEngine eng, MoveContext c) {
        MovingData d = c.data;
        PosInfo f = c.from, t = c.to;
        boolean active = eng.active(c.player, CheckType.MOVING_CREATIVEFLY) && d.leniencyTicks == 0 && f.loaded && t.loaded;
        double fly = Math.max(0.05, c.handle.abilities.flySpeed);
        double flyAccel = fly * 2.0;
        double walkAccel = c.horizontalAccel();
        double accel = Math.max(flyAccel, walkAccel);
        double jumpBoost = f.ground ? 0.2 : 0.0;
        double extra = c.push + (d.unreported ? SurvivalFly.UNREPORTED : 0.0) + (f.liquidLoose || t.liquidLoose ? 0.03 : 0.0);

        double hNormal = d.nextHBase + accel + jumpBoost;
        double hSet = 0, hAdd = 0;
        double setHi = Double.NEGATIVE_INFINITY, addHi = 0;
        for (VelocityTracker.Entry v : c.velocities) {
            if (v.additive) { hAdd += v.h; if (v.y > 0) addHi += v.y; }
            else { hSet = Math.max(hSet, v.h + accel + jumpBoost); setHi = Math.max(setHi, v.y); }
        }
        double hLimit = (Math.max(hNormal, hSet) + hAdd + extra) * 1.003 + 0.002;
        double vl = 0;
        StringBuilder info = new StringBuilder();

        if (c.hasPos && active && c.hDist > hLimit) {
            double excess = c.hDist - hLimit;
            if (excess <= d.hBuffer) d.hBuffer -= excess;
            else {
                vl += (excess - d.hBuffer) * 100.0;
                d.hBuffer = 0;
                info.append(String.format(" hDist %.4f > %.4f", c.hDist, hLimit));
            }
        } else if (c.hasPos) {
            d.hBuffer = Math.min(MovingData.HBUF_MAX, d.hBuffer + 0.002);
        }

        double key = fly * 3.0;
        double upper = d.nextVHigh + key;
        if (f.ground) upper = Math.max(upper, c.jumpMotion);
        if (f.climbable || t.climbable) upper = Math.max(upper, SurvivalFly.CLIMB_UP);
        if (f.liquidLoose || t.liquidLoose) upper = Math.max(upper, 0.34);
        if (setHi > Double.NEGATIVE_INFINITY) upper = Math.max(upper, setHi);
        upper += addHi + (d.unreported ? SurvivalFly.UNREPORTED : 0.0) + 0.001;
        if (c.hasPos && active) {
            boolean ok = c.dy <= upper || (t.ground && c.dy <= SurvivalFly.STEP + 1.0E-4);
            if (!ok) {
                vl += (c.dy - upper) * 100.0;
                info.append(String.format(" dY %.4f > %.4f", c.dy, upper));
            }
        }

        // Chain: valid for both flight (0.6 * motion) and survival physics in case flight ended client-side.
        double motion = c.hasPos ? c.hDist : Math.min(hNormal, SurvivalFly.UNREPORTED);
        d.nextHBase = motion * 0.91;
        double dyHi = c.hasPos ? c.dy : Math.min(upper, SurvivalFly.UNREPORTED);
        double dyLo = c.hasPos ? c.dy : -SurvivalFly.UNREPORTED;
        double nHi = Math.max(dyHi * 0.6, (dyHi - SurvivalFly.GRAVITY) * SurvivalFly.DRAG);
        double nLo = Math.min(dyLo * 0.6 - key, (dyLo - SurvivalFly.GRAVITY) * SurvivalFly.DRAG);
        if (t.ground) { nHi = Math.max(nHi, SurvivalFly.GROUND_MOTION); nLo = Math.min(nLo, SurvivalFly.GROUND_MOTION); }
        if (t.climbable) nHi = Math.max(nHi, SurvivalFly.CLIMB_UP);
        if (t.liquidLoose) nHi = Math.max(nHi, 0.3);
        d.nextVHigh = nHi;
        d.nextVLow = nLo;

        if (vl > 0) {
            c.violated = true;
            String details = "creativefly:" + info + String.format(" (flySpeed %.3f%s, ping %dms)", fly,
                    c.sprinting ? ", sprint" : "", c.ping);
            boolean cancel = eng.flag(c.player, CheckType.MOVING_CREATIVEFLY, Math.min(vl, 50.0), details);
            if (cancel) c.requestSetback(d.setBack != null ? d.setBack : c.fromLocation());
        }
    }
}
