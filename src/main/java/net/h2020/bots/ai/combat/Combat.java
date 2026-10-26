package net.h2020.bots.ai.combat;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Raycast;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 1.8 PvP: target tracking, sprint approach, A/D strafing with human switch timing, W-tap/S-tap sprint resets after
 * landed hits, occasional jump crits, block-hitting, keeping the combo distance (~2.8-3.0), bow with draw time and
 * leading/arrow drop, snowballs/eggs and fireballs to knock bridgers into the void, deliberate void-side
 * positioning, golden apples and retreating at low health, pearls (rarely). Skill comes from the persona; the
 * controller's human aim model does the actual mouse work.
 */
public final class Combat {
    private final BotContext ctx;
    private Player target;
    private long engagedAt;

    // options from the brain
    public boolean allowRod = true;
    public boolean allowBow = true, allowThrowables = true, allowFireball = false, allowPearl = false, allowGapple = true;
    public boolean allowChase = true;
    public NavOptions chaseOptions = chase(NavOptions.walk());

    /** Chasing must not path around the very player we chase. */
    public static NavOptions chase(NavOptions o) { o.avoidEnemies = false; return o; }
    /** Do not chase further than this from where the fight started (stay near the bed, the island...). */
    public double leash = 40;
    private Location anchor;

    // melee state
    private int strafeDir = 1;
    private long nextStrafeSwitch;
    private int wtapTicks;
    private boolean sTapNow;
    private int blockHitTicks;
    private int critCooldown;
    private double cpsNow;
    private long lastHitLanded = -1000, lastHurt = -1000;
    private int comboCount;

    // ranged / items state
    private int eatTicks;
    private long lastEat = -10000;
    private int drawTicks, drawTarget;
    private int rangedCooldown;
    private int throwsLeft;
    private boolean usingBowThisFight;
    private boolean pearlUsed;
    private int fireballCooldown;
    private int rodTicks, rodCooldown;

    private boolean retreating;
    // progress toward the target: a fight that never gets into reach (target across the void, behind a wall, no path)
    // is given up instead of standing there forever
    private double bestDist;
    private long bestDistTick;
    private final java.util.Map<java.util.UUID, Long> ignoreUntil = new java.util.HashMap<>();
    private int losCache = Raycast.CLEAR;
    private long losTick = -100;

    public Combat(BotContext ctx) { this.ctx = ctx; }

    public Player target() { return target; }
    public boolean engaged() { return target != null; }
    public boolean retreating() { return retreating; }
    public boolean eating() { return eatTicks > 0; }

    public void engage(Player t) {
        if (t == null || t == target) return;
        target = t;
        engagedAt = ctx.tick;
        anchor = ctx.pos();
        comboCount = 0;
        bestDist = Double.MAX_VALUE;
        bestDistTick = ctx.tick;
        throwsLeft = AIMath.range(ctx.rng, 2, 6);
        usingBowThisFight = ctx.rng.nextDouble() < ctx.persona.bowLiking;
        pearlUsed = false;
        retreating = false;
        nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
        cpsNow = sampleCps();
    }

    /** Recently given up on (unreachable): brains should not re-engage unless it attacks us. */
    public boolean ignoring(Player p) {
        if (p == null) return false;
        Long until = ignoreUntil.get(p.getUniqueId());
        return until != null && ctx.tick < until;
    }

    private void giveUp() {
        if (target != null) ignoreUntil.put(target.getUniqueId(), ctx.tick + 400);
        disengage();
        if (ctx.nav.active() || ctx.nav.failed()) ctx.nav.stop();
    }

    public void disengage() {
        target = null;
        retreating = false;
        drawTicks = 0;
        if (eatTicks > 0) eatTicks = 0;
    }

    /** Players start clicking a bit before 3-block reach; better players waste fewer swings. */
    private double clickRange() {
        return 3.35 + 0.45 * (1.0 - ctx.persona.skill.overall);
    }

    private double sampleCps() {
        return AIMath.gauss(ctx.rng, ctx.persona.skill.cps, 0.9, 3.5, 16);
    }

    public void onHitLanded(Player victim) {
        lastHitLanded = ctx.tick;
        if (victim != target) return;
        comboCount++;
        if (ctx.rng.nextDouble() < ctx.persona.wtapChance) {
            int delay = ctx.rng.nextDouble() < 0.7 ? 0 : 1;
            wtapTicks = AIMath.range(ctx.rng, 2, 4) + delay;
            sTapNow = ctx.persona.sTapper && ctx.rng.nextDouble() < 0.7;
        }
        if (ctx.persona.blockHitter && ctx.rng.nextDouble() < 0.55) blockHitTicks = AIMath.range(ctx.rng, 2, 4);
    }

    public void onHurt(Player attacker) {
        lastHurt = ctx.tick;
        comboCount = 0;
        if (target == null && attacker != null) engage(attacker);
        else if (attacker != null && attacker != target && ctx.pos().distance(attacker.getLocation()) < 3.5
                && target != null && ctx.pos().distance(target.getLocation()) > 5) engage(attacker);
    }

    private boolean valid(Player t) {
        return t != null && t.isOnline() && !t.isDead() && t.getWorld() == ctx.player.getWorld()
                && t.getGameMode() != GameMode.SPECTATOR && ctx.perception.filter().relevant(t);
    }

    // ------------------------------------------------------------------------------------------------ tick

    public void tick() {
        if (!valid(target)) { disengage(); return; }
        Location me = ctx.pos();
        Location tl = target.getLocation();
        double d = me.distance(tl);
        Sighting s = ctx.perception.get(target);
        if (d > 60 || (s != null && !s.visible && s.unseenSeconds(ctx.tick) > 6)) { disengage(); return; }
        if (anchor != null && anchor.getWorld() == tl.getWorld() && anchor.distance(tl) > leash && d > 8) { disengage(); return; }
        if (critCooldown > 0) critCooldown--;
        if (rangedCooldown > 0) rangedCooldown--;
        if (fireballCooldown > 0) fireballCooldown--;
        if (rodCooldown > 0) rodCooldown--;
        if (ctx.tick % 30 == 0) cpsNow = sampleCps();
        if (d < bestDist - 0.5) { bestDist = d; bestDistTick = ctx.tick; }
        long noHitsFor = ctx.tick - Math.max(Math.max(lastHitLanded, lastHurt), engagedAt);
        // closing in, or trading hits, is progress; standing in "reach" swinging at a wall/pane is not
        long stalledFor = Math.min(ctx.tick - bestDistTick, noHitsFor);
        // no progress for 10 s and no hits either way: unreachable, find something else to do
        if (stalledFor > 200 && noHitsFor > 200) { giveUp(); return; }

        boolean los = los(tl);

        // eating a golden apple
        if (eatTicks > 0) {
            eatTicks--;
            if (Inv.holding(ctx.player, Inv.GAPPLE)) ctx.motor.holdRight();
            backOff(tl, d);
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            if (eatTicks == 0) Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            return;
        }
        double hp = ctx.health();
        if (allowGapple && hp <= 10 && ctx.tick - lastEat > 70 && Inv.has(ctx.player, Inv.GAPPLE) && (d > 4.5 || hp <= 6)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.GAPPLE, 7)) {
                eatTicks = 34 + AIMath.range(ctx.rng, 0, 6);
                lastEat = ctx.tick;
            }
            backOff(tl, d);
            return;
        }
        // retreat when losing
        retreating = hp <= ctx.persona.retreatHealth && target.getHealth() > hp + 3 && d < 12;
        if (retreating && ctx.persona.skill.aggression < 0.85) {
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            if (allowPearl && !pearlUsed && hp <= 4 && Inv.has(ctx.player, Inv.PEARL) && ctx.rng.nextDouble() < 0.05) {
                escapePearl(tl);
                return;
            }
            backOff(tl, d);
            if (d < 3.2) ctx.motor.holdLeft(cpsNow);
            return;
        }

        // ranged options
        if (los && ranged(d, tl)) return;
        drawTicks = 0;

        // chase when far / unreachable directly
        // (also when walking straight at them got us no closer for a while: a gap, a wall or the void edge is in between)
        if (d > 6.5 || Math.abs(tl.getY() - me.getY()) > 2.5 || (stalledFor > 50 && (d > clickRange() || noHitsFor > 60))) {
            if (allowChase) {
                ctx.nav.follow(tl, 1.8, chaseOptions);
                ctx.nav.tick();
                if (ctx.nav.failed()) { giveUp(); return; }
            }
            // bridging/pillaring toward them: eyes stay on the blocks (the bridger glances up on its own)
            if (los && d < 14 && (!ctx.nav.doingAction() || d < 4.5)) ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            // (mid-bridge the hand stays on the blocks: swapping to the sword every tick made the bridger right-click
            // with a sword and never place)
            if (!ctx.nav.doingAction()) {
                if (d < clickRange()) ctx.motor.holdLeft(cpsNow);
                Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            }
            return;
        }
        if (ctx.nav.active()) ctx.nav.stop();
        melee(tl, d);
    }

    private boolean los(Location tl) {
        if (ctx.tick - losTick >= 5) {
            int r = Raycast.lineOfSight(ctx.player.getWorld(), ctx.eye(), tl.clone().add(0, 1.4, 0));
            if (r != Raycast.UNKNOWN) losCache = r;
            losTick = ctx.tick;
        }
        return losCache == Raycast.CLEAR;
    }

    // ------------------------------------------------------------------------------------------------ melee

    private void melee(Location tl, double d) {
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        if (Inv.bestMeleeHotbar(ctx.player) >= 0) {
            int slot = Inv.bestMeleeHotbar(ctx.player);
            if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        } else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
        ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);

        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / len, uz = dz / len;

        // void knock: approach from the side opposite to the void next to the target
        Vector voidDir = voidDirection(tl);
        double mx = ux, mz = uz;
        if (voidDir != null && d > 2.2) {
            double wantX = tl.getX() - voidDir.getX() * 2.6, wantZ = tl.getZ() - voidDir.getZ() * 2.6;
            double wx = wantX - me.getX(), wz = wantZ - me.getZ();
            double wl = Math.sqrt(wx * wx + wz * wz);
            if (wl > 0.8) { mx = wx / wl; mz = wz / wl; }
        }

        boolean onBridge = wv.voidSides(floor(me.getX()), floor(me.getY() + 0.01), floor(me.getZ())) >= 2;
        boolean strafe = ctx.persona.strafes && !onBridge && d < 3.6;
        if (ctx.tick >= nextStrafeSwitch) {
            if (ctx.rng.nextDouble() < 0.8) strafeDir = -strafeDir;
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
        }
        double fwd = 1;
        // 1.8 players keep holding W through a combo (sprint is reset by the W/S-tap below); they only ease off when
        // they're basically inside the opponent
        if (d < 1.7) fwd = 0;
        if (d < 1.0) fwd = -1;
        if (wtapTicks > 0) { wtapTicks--; fwd = sTapNow ? -1 : 0; }

        // world move direction: forward toward target plus strafe component
        double sx = 0, sz = 0;
        if (strafe) { sx = -uz * strafeDir; sz = ux * strafeDir; }
        double wx = mx * fwd + sx * 0.9, wz = mz * fwd + sz * 0.9;
        if (!safe(me, wx, wz)) {
            // try the other strafe direction, then straight, then nothing
            double ax = mx * fwd - sx * 0.9, az = mz * fwd - sz * 0.9;
            if (strafe && safe(me, ax, az)) { strafeDir = -strafeDir; wx = ax; wz = az; }
            else if (safe(me, mx * fwd, mz * fwd)) { wx = mx * fwd; wz = mz * fwd; }
            else { wx = 0; wz = 0; ctx.motor.sneak(onBridge); }
        }
        if (Math.abs(wx) + Math.abs(wz) > 1e-3) ctx.motor.moveToward(wx, wz);
        ctx.motor.sprint(fwd > 0 && wtapTicks == 0);

        // jump crits / sprint-jump in
        if (ctx.c.onGround() && critCooldown == 0) {
            boolean crit = d < 3.1 && ctx.tick - lastHurt > 10 && ctx.rng.nextDouble() < ctx.persona.jumpCritChance * 0.25;
            boolean closeIn = d > 4.5 && ctx.persona.sprintJumper && ctx.rng.nextDouble() < 0.15;
            // beginners jump around a lot in fights (it doesn't help them)
            if (!crit && !closeIn && ctx.persona.skill.overall < 0.35 && ctx.rng.nextDouble() < 0.06) crit = true;
            if ((crit || closeIn) && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
                ctx.motor.jump(true);
                critCooldown = AIMath.range(ctx.rng, 10, 24);
            }
        }

        // clicking / block-hitting
        if (blockHitTicks > 0) {
            blockHitTicks--;
            if (Inv.holding(ctx.player, Inv.SWORD)) ctx.motor.holdRight();
        } else if (d < clickRange()) {
            ctx.motor.holdLeft(cpsNow);
        }
    }

    /** Is moving in world direction (x,z) safe from walking off into the void? */
    private boolean safe(Location me, double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-3) return true;
        WorldView wv = ctx.world();
        double ahead = 0.9;
        int cx = floor(me.getX() + x / len * ahead), cz = floor(me.getZ() + z / len * ahead), cy = floor(me.getY() + 0.01);
        if (wv.standable(cx, cy, cz) || wv.standable(cx, cy - 1, cz) || wv.standable(cx, cy + 1, cz)) return true;
        if (!wv.passable(cx, cy, cz)) return true;
        return wv.landing(cx, cy - 1, cz, 4) != Integer.MIN_VALUE;
    }

    /** Average horizontal direction from the target toward adjacent void (null if none within 2 blocks). */
    private Vector voidDirection(Location tl) {
        WorldView wv = ctx.world();
        int x = floor(tl.getX()), y = floor(tl.getY() + 0.01), z = floor(tl.getZ());
        double vx = 0, vz = 0;
        int n = 0;
        for (int r = 1; r <= 2; r++) {
            int[][] dirs = {{r, 0}, {-r, 0}, {0, r}, {0, -r}};
            for (int[] dd : dirs) {
                if (wv.passable(x + dd[0], y, z + dd[1]) && wv.voidBelow(x + dd[0], y, z + dd[1], 20)) {
                    vx += dd[0] / (double) r; vz += dd[1] / (double) r; n++;
                }
            }
        }
        if (n == 0) return null;
        double l = Math.sqrt(vx * vx + vz * vz);
        if (l < 1e-3) return null;
        return new Vector(vx / l, 0, vz / l);
    }

    private void backOff(Location tl, double d) {
        Location me = ctx.pos();
        double dx = me.getX() - tl.getX(), dz = me.getZ() - tl.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double wx = dx / len, wz = dz / len;
        if (!safe(me, wx, wz)) {
            // slide sideways instead
            double ax = -wz, az = wx;
            if (safe(me, ax, az)) { wx = ax; wz = az; }
            else if (safe(me, -ax, -az)) { wx = -ax; wz = -az; }
            else { ctx.motor.sneak(true); return; }
        }
        ctx.motor.moveToward(wx, wz);
        // running away facing forward is faster: turn around when far enough
        if (d > 5 && eatTicks == 0) {
            ctx.motor.aimRotation(Motor.P_COMBAT + 1, AIMath.yawOf(wx, wz), 10, AimIntent.NAVIGATE, 8);
            ctx.motor.sprint(true);
        }
    }

    // ------------------------------------------------------------------------------------------------ ranged

    private boolean ranged(double d, Location tl) {
        // fishing rod: cast as they come into range (pulls them in / breaks their sprint), then swap back to the sword
        // (1.8 drops the hook when the rod leaves the hand). Only from the hotbar: no inventory clicks mid-fight.
        if (rodTicks > 0) {
            rodTicks--;
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            Location me = ctx.pos();
            double ax = tl.getX() - me.getX(), az = tl.getZ() - me.getZ();
            if (d > 2.6 && safe(me, ax, az)) { ctx.motor.moveToward(ax, az); ctx.motor.sprint(true); }
            if (rodTicks == 0) { Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0); rodCooldown = AIMath.range(ctx.rng, 25, 80); }
            return true;
        }
        if (allowRod && ctx.persona.rodder && rodCooldown == 0 && d > 3.4 && d < 7.5 && Inv.findHotbar(ctx.player, Inv.ROD) >= 0
                && ctx.rng.nextDouble() < 0.08) {
            if (Inv.equip(ctx.player, ctx.c, Inv.ROD, 1) && Inv.holding(ctx.player, Inv.ROD)) {
                ctx.motor.aimPoint(Motor.P_COMBAT, lead(tl, d, 1.4, 0.04), AimIntent.COMBAT, 0.3);
                ctx.c.rightClick();
                rodTicks = AIMath.range(ctx.rng, 5, 10);
            } else rodCooldown = 4; // selected this tick: cast next time it comes up
            return true;
        }
        // fireball at bridgers / people on edges (Bed Wars)
        if (allowFireball && fireballCooldown == 0 && d > 6 && d < 26 && voidDirection(tl) != null
                && Inv.has(ctx.player, Inv.FIREBALL) && ctx.rng.nextDouble() < 0.08) {
            if (Inv.equip(ctx.player, ctx.c, Inv.FIREBALL, 6)) {
                ctx.motor.aimPoint(Motor.P_COMBAT, tl.clone().add(0, 0.4, 0), AimIntent.COMBAT, 0.3);
                if (ctx.c.aimSettled()) { ctx.c.rightClick(); fireballCooldown = 50; }
            }
            return true;
        }
        // snowballs / eggs for void knockback
        if (allowThrowables && throwsLeft > 0 && d > 3.5 && d < 22 && voidDirection(tl) != null && Inv.has(ctx.player, Inv.THROWABLE)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.THROWABLE, 5)) {
                ctx.motor.aimPoint(Motor.P_COMBAT, lead(tl, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                if (rangedCooldown == 0 && ctx.c.aimSettled()) {
                    ctx.c.rightClick();
                    throwsLeft--;
                    rangedCooldown = AIMath.range(ctx.rng, 4, 9);
                }
            }
            standOrStrafe();
            return true;
        }
        // engage pearl (tryhards)
        if (allowPearl && !pearlUsed && ctx.persona.tryhard && d > 18 && d < 32 && Inv.has(ctx.player, Inv.PEARL)
                && ctx.rng.nextDouble() < 0.01) {
            if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                Location land = tl.clone().add(ctx.pos().toVector().subtract(tl.toVector()).normalize().multiply(2.5));
                ctx.motor.aimPoint(Motor.P_COMBAT, lead(land, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                if (ctx.c.aimSettled()) { ctx.c.rightClick(); pearlUsed = true; }
            }
            return true;
        }
        // bow
        boolean bowRange = d > 9 && d < 45;
        if (allowBow && usingBowThisFight && (bowRange || drawTicks > 0) && Inv.has(ctx.player, Inv.BOW) && Inv.has(ctx.player, Inv.ARROW)) {
            if (d < 6) { drawTicks = 0; return false; } // they closed in: switch to sword
            if (!Inv.equip(ctx.player, ctx.c, Inv.BOW, 2)) return true;
            if (drawTicks == 0) {
                if (rangedCooldown > 0) { standOrStrafe(); return true; }
                drawTarget = AIMath.range(ctx.rng, 15, 24);
            }
            ctx.motor.aimPoint(Motor.P_COMBAT, lead(tl, d, 2.9, 0.05), AimIntent.COMBAT, 0.2);
            drawTicks++;
            if (drawTicks < drawTarget || !ctx.c.aimSettled()) {
                ctx.motor.holdRight();
                if (drawTicks > 60) { drawTicks = 0; rangedCooldown = 10; }
            } else {
                // release (not holding right this tick)
                drawTicks = 0;
                rangedCooldown = AIMath.range(ctx.rng, 6, 14);
            }
            standOrStrafe();
            return true;
        }
        return false;
    }

    private void standOrStrafe() {
        if (!ctx.persona.strafes) return;
        if (ctx.tick >= nextStrafeSwitch) {
            strafeDir = -strafeDir;
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs * 1.5);
        }
        float yaw = ctx.c.yaw();
        double lx = Math.cos(Math.toRadians(yaw)) * strafeDir, lz = Math.sin(Math.toRadians(yaw)) * strafeDir;
        if (safe(ctx.pos(), lx, lz)) ctx.motor.move(0, strafeDir);
    }

    /** Aim point leading the target for a projectile with speed v (blocks/tick) and gravity g (blocks/tick^2). */
    private Location lead(Location tl, double d, double v, double g) {
        Sighting s = ctx.perception.get(target);
        Vector vel = s != null ? s.lastVel : new Vector();
        double t = d / (v * 0.96);
        double skill = 0.55 + 0.45 * ctx.persona.skill.aim;
        double drop = 0.5 * g * t * t * 1.1;
        double err = (1 - ctx.persona.skill.aim) * d * 0.035;
        return tl.clone().add(vel.getX() * t * skill + ctx.rng.nextGaussian() * err,
                1.1 + drop + ctx.rng.nextGaussian() * err * 0.6,
                vel.getZ() * t * skill + ctx.rng.nextGaussian() * err);
    }

    private void escapePearl(Location tl) {
        Location me = ctx.pos();
        Vector away = me.toVector().subtract(tl.toVector()).setY(0);
        if (away.lengthSquared() < 1e-3) return;
        away.normalize();
        // find a safe landing ~12 blocks away
        WorldView wv = ctx.world();
        for (int dist = 14; dist >= 6; dist -= 2) {
            int x = floor(me.getX() + away.getX() * dist), z = floor(me.getZ() + away.getZ() * dist);
            int land = wv.landing(x, floor(me.getY()) + 3, z, 8);
            if (land == Integer.MIN_VALUE) continue;
            if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                Location tgt = new Location(me.getWorld(), x + 0.5, land, z + 0.5);
                ctx.motor.aimPoint(Motor.P_COMBAT + 2, lead(tgt, dist, 1.5, 0.03), AimIntent.FLICK, 0.3);
                if (ctx.c.aimSettled()) { ctx.c.rightClick(); pearlUsed = true; }
            }
            return;
        }
    }

    public boolean holdingMaterial(Material m) {
        return ctx.player.getItemInHand() != null && ctx.player.getItemInHand().getType() == m;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
