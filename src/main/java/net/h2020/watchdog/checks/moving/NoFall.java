package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;
import net.minecraft.server.v1_8_R3.DamageSource;

/**
 * NCP NoFall. The server only trusts the client's onGround flag (PlayerConnection -> EntityPlayer.a(dy, onGround)),
 * so a client that claims ground in mid-air resets its fall distance, and one that never claims ground on landing
 * never takes damage. NoFall keeps its own fall distance from the server-side positions and ground collision:
 * - ground claimed while falling with nothing below ("ground spoof") is a violation;
 * - on the real landing, if the spoof reset the server's fall distance, the server's distance is restored right
 *   before it applies the landing (live PlayerMoveEvent), so the vanilla damage code deals the damage;
 * - a landing the client keeps denying for 2 ticks is damaged by us, NCP-style (DamageSource.FALL, so games see a
 *   normal fall death).
 * Water, ladders/vines, webs, slime blocks, allowFlight and creative reset/skip exactly like vanilla.
 * Optional 2020 blind spot (watchdog.movement.legacy-2020-nofall-gap): see legacyReset.
 * Legit clients never trigger either path, so vanilla fall damage stays untouched for them.
 */
final class NoFall {
    private NoFall() {}

    static void check(MovingEngine eng, MoveContext c) {
        MovingData d = c.data;
        if (c.creativeLike || c.flyingMode || c.player.getAllowFlight() || c.handle.abilities.isInvulnerable) {
            d.resetNoFall();
            return;
        }
        PosInfo f = c.from, t = c.to;
        if (f.liquidLoose || t.liquidLoose || f.climbable || t.climbable || f.web || t.web) {
            d.resetNoFall();
            return;
        }
        if (c.legacyHover) {
            // 2020 hover fly (legacy-2020-hover-gap): the +-1/16 rhythm nets no height, but vanilla only counts the
            // down steps, so without this a hover builds several blocks of fall distance and damages you on landing.
            d.resetNoFall();
            c.handle.fallDistance = 0;
            return;
        }
        boolean active = eng.active(c.player, CheckType.MOVING_NOFALL) && f.loaded && t.loaded;
        boolean claim = c.entry.ground;

        if (d.noFallPending > 0) {
            double pending = d.noFallPending;
            if (claim) {
                // Client finally reports the landing: make sure the server deals the full distance.
                if (c.live) {
                    if (c.handle.fallDistance < pending - 0.5) c.handle.fallDistance = (float) pending;
                } else if (active && d.noFallPendingSpoofed) {
                    // Already applied by the server with a spoof-reset distance (a legit client's own distance
                    // was intact and the server dealt it itself).
                    deal(c, pending);
                }
                d.noFallPending = 0;
            } else if (t.ground) {
                if (++d.noFallPendingTicks >= 2) {
                    d.noFallPending = 0;
                    if (active) {
                        deal(c, pending);
                        eng.flag(c.player, CheckType.MOVING_NOFALL, Math.min(10.0, pending - 3.0),
                                String.format("nofall: landed after %.2f blocks without claiming ground", pending));
                    }
                }
            } else {
                // Slid off the edge it landed on: keep falling with the old distance.
                d.noFallDist = pending;
                d.noFallPending = 0;
            }
        }

        if (t.ground) {
            double dist = d.noFallDist;
            boolean slime = t.onSlime && !c.sneaking; // BlockSlime.fallOn: no damage unless sneaking
            if (dist > 3.0 && !slime) {
                if (claim) {
                    if (d.spoofTicks >= 1) {
                        if (c.live) {
                            if (c.handle.fallDistance < dist - 0.5) c.handle.fallDistance = (float) dist;
                        } else if (active) {
                            deal(c, dist);
                        }
                        if (active) {
                            eng.flag(c.player, CheckType.MOVING_NOFALL, Math.min(10.0, dist - 3.0),
                                    String.format("nofall: fall distance %.2f reset by %d ground-spoof tick(s)", dist, d.spoofTicks));
                        }
                    }
                } else {
                    d.noFallPending = dist;
                    d.noFallPendingTicks = 0;
                    d.noFallPendingSpoofed = d.spoofTicks >= 1;
                }
            }
            d.noFallDist = 0;
            d.spoofTicks = 0;
            d.spoofFlagged = false;
            d.noFallLegacyPrev = false;
            return;
        }

        double before = d.noFallDist; // what the server's own fall distance should be before this tick
        if (c.hasPos && c.dy < 0) d.noFallDist += -c.dy;
        boolean legacyPrev = d.noFallLegacyPrev;
        d.noFallLegacyPrev = false;
        if (c.hasPos && claim && !f.ground && !t.nearGround && c.dy < -0.05) {
            if (eng.config.legacyNoFallGap && legacyReset(d, before, -c.dy, legacyPrev)) {
                // 2020 blind spot (legacy-2020-nofall-gap): vanilla resets the fall distance on this claim exactly
                // like Hypixel's 2020 Watchdog let it, so our own distance restarts from here too (this tick's dy is
                // dropped by vanilla as well). No flag, and no restore/damage on the landing.
                d.noFallDist = 0;
                d.noFallLegacyPrev = true;
                return;
            }
            d.spoofTicks++;
            if (active && d.spoofTicks >= 3 && d.noFallDist > 1.0 && !d.spoofFlagged) {
                d.spoofFlagged = true;
                eng.flag(c.player, CheckType.MOVING_NOFALL, 2.0,
                        String.format("nofall: onGround while falling (%d ticks, %.2f blocks)", d.spoofTicks, d.noFallDist));
            }
        }
    }

    /**
     * The 2020 "Hypixel" NoFall: one onGround=true on the regular movement packet once the fall since the last reset
     * passes ~2.5 blocks (client fallDistance > 2.5..3, then reset). Accepted only when
     * - no off-pattern ground spoof happened in this fall (constant onGround / packet NoFall stays fully judged);
     * - the previous packet did not claim ground, unless it was itself such a reset (at high fall speed every tick
     *   covers more than 2.5 blocks, so the pattern resets every tick);
     * - the distance vanilla discards is <= 3 (the reset comes before the damage threshold, never at the landing of
     *   a longer fall) and, including this tick, at least ~2.5 blocks were fallen (not an every-tick spoof).
     */
    private static boolean legacyReset(MovingData d, double before, double fallThisTick, boolean legacyPrev) {
        if (d.spoofTicks > 0) return false;
        if (d.prevClientGround && !legacyPrev) return false;
        return before <= 3.0 + 1.0E-3 && before + fallThisTick >= LEGACY_MIN_FALL;
    }

    /** Smallest fall (blocks, including the claim tick) the 2020 pattern resets after (clients used 2.5-3). */
    private static final double LEGACY_MIN_FALL = 2.4;

    /** EntityLiving.e(fallDistance, 1.0): ceil(distance - 3 - jumpBoostLevel) through DamageSource.FALL. */
    static void deal(MoveContext c, double dist) {
        double reduce = c.jumpAmp >= 0 ? c.jumpAmp + 1 : 0;
        int dmg = (int) Math.ceil(dist - 3.0 - reduce);
        c.handle.fallDistance = 0;
        if (dmg > 0 && !c.player.isDead()) c.handle.damageEntity(DamageSource.FALL, (float) dmg);
    }
}
