package net.h2020.bots.ai.combat;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import net.h2020.bots.ai.items.ItemUse;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Raycast;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 1.8 PvP: target tracking, sprint approach, A/D strafing with human switch timing, W-tap/S-tap sprint resets after
 * landed hits, occasional jump crits, block-hitting, keeping the combo distance (~2.8-3.0), bow with draw time and
 * leading/arrow drop, snowballs/eggs and fireballs to knock bridgers into the void, deliberate void-side
 * positioning, golden apples and retreating at low health, pearls. Skill comes from the persona; the controller's
 * human aim model does the actual mouse work.
 * <p>
 * The 2020 behaviour set ({@link CombatStats#v2}, default on) adds, scaled by skill ({@link Style}): circle strafing
 * that keeps the void out of your back, jump-resets on incoming hits, combo-locking a target in hitstun, breaking
 * out of a combo (strafe switch / rod reset), hit-and-run against better-geared players, block / water / pearl
 * clutches when knocked off ({@link Clutch}), towering up and walling or cutting bridges when fleeing
 * ({@link BlockTactics}), potions / gapples / lava / flint &amp; steel / TNT ({@link ItemUse}), ballistic projectile
 * aim with lead and per-throw error ({@link Ballistics}), punch-bow and snowball spam at people on edges, and
 * pearls to escape, to cross a gap to someone unreachable, and to get back from the void.
 */
public final class Combat {
    private final BotContext ctx;
    public final Style style;
    private final Clutch clutch;
    private final BlockTactics tactics;
    public final ItemUse items;
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
    private int hitsTaken;           // hits taken in a row without landing one
    private boolean breakOut;        // switch strafe direction now (being comboed)
    private int runTicks;            // hit-and-run: backing out
    private double gearDiff;         // target gear - our gear
    private long gearTick = -100;
    private boolean edgeBehind;

    // knockback measurement (stats)
    private Location kbFrom;
    private double kbUx, kbUz;
    private long kbTick = -1;
    private boolean kbJumped;

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
    private int rodWant;             // v2: rod selected, cast once it's in the hand
    private String rodStat;

    private boolean retreating;
    // progress toward the target: a fight that never gets into reach (target across the void, behind a wall, no path)
    // is given up instead of standing there forever
    private double bestDist;
    private long bestDistTick;
    private final java.util.Map<java.util.UUID, Long> ignoreUntil = new java.util.HashMap<>();
    private int losCache = Raycast.CLEAR;
    private long losTick = -100;

    public Combat(BotContext ctx) {
        this.ctx = ctx;
        this.style = new Style(ctx.persona);
        this.clutch = new Clutch(ctx, style);
        this.tactics = new BlockTactics(ctx, style);
        this.items = new ItemUse(ctx, style);
    }

    public Player target() { return target; }
    public boolean engaged() { return target != null; }
    public boolean retreating() { return retreating; }
    public boolean eating() { return eatTicks > 0 || items.eating(); }

    public void engage(Player t) {
        if (t == null || t == target) return;
        target = t;
        engagedAt = ctx.tick;
        anchor = ctx.pos();
        comboCount = 0;
        hitsTaken = 0;
        runTicks = 0;
        gearTick = -100;
        bestDist = Double.MAX_VALUE;
        bestDistTick = ctx.tick;
        throwsLeft = AIMath.range(ctx.rng, 2, 6);
        usingBowThisFight = ctx.rng.nextDouble() < ctx.persona.bowLiking;
        pearlUsed = false;
        retreating = false;
        nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
        cpsNow = sampleCps();
        items.onFightStart();
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
        if (tactics.busy()) tactics.cancel();
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
        hitsTaken = 0;
        if (victim != target) return;
        comboCount++;
        if (ctx.rng.nextDouble() < ctx.persona.wtapChance) {
            int delay = ctx.rng.nextDouble() < 0.7 ? 0 : 1;
            wtapTicks = AIMath.range(ctx.rng, 2, 4) + delay;
            sTapNow = ctx.persona.sTapper && ctx.rng.nextDouble() < 0.7;
            // (v2) no backwards tap with the void behind us
            if (CombatStats.v2 && edgeBehind) sTapNow = false;
            CombatStats.inc(sTapNow ? "stap" : "wtap");
        }
        if (ctx.persona.blockHitter && ctx.rng.nextDouble() < 0.55) blockHitTicks = AIMath.range(ctx.rng, 2, 4);
        // hit and run against someone with better gear: land it, back out, let them come again
        if (CombatStats.v2 && style.hitAndRun && gearDiff > 3 && ctx.rng.nextDouble() < 0.55) {
            runTicks = AIMath.range(ctx.rng, 5, 11);
            CombatStats.inc("hitrun");
        }
        if (CombatStats.v2 && comboCount == 3) CombatStats.inc("combo3");
    }

    public void onHurt(Player attacker) {
        lastHurt = ctx.tick;
        comboCount = 0;
        hitsTaken++;
        if (hitsTaken >= 2) breakOut = true;
        clutch.onKnock();
        // knockback measurement (melee hits only)
        if (attacker != null && attacker.getWorld() == ctx.player.getWorld()) {
            Location me = ctx.pos(), al = attacker.getLocation();
            double dx = me.getX() - al.getX(), dz = me.getZ() - al.getZ();
            double l = Math.sqrt(dx * dx + dz * dz);
            if (l < 4.5 && l > 1e-3 && kbTick < 0) {
                kbFrom = me.clone(); kbUx = dx / l; kbUz = dz / l; kbTick = ctx.tick; kbJumped = false;
            }
        }
        if (target == null && attacker != null) engage(attacker);
        else if (attacker != null && attacker != target && ctx.pos().distance(attacker.getLocation()) < 3.5
                && target != null && ctx.pos().distance(target.getLocation()) > 5) engage(attacker);
    }

    /** A projectile of ours hit someone (stats). */
    public void onProjectileHit(String what) { CombatStats.inc("hit." + what); }

    /** A Watchdog flag on this bot (stats). */
    public void onFlag(String check) {
        CombatStats.inc("flag.all");
        CombatStats.inc("flag.c." + check);
        if (engaged() || ctx.tick - Math.max(lastHurt, lastHitLanded) < 60) { CombatStats.inc("flag.fight"); CombatStats.inc("flag.f." + check); }
    }

    /** The bot died (stats + clutch bookkeeping). */
    public void onDeath(boolean voided) { clutch.onDeath(voided); kbTick = -1; }

    private boolean valid(Player t) {
        return t != null && t.isOnline() && !t.isDead() && t.getWorld() == ctx.player.getWorld()
                && t.getGameMode() != GameMode.SPECTATOR && ctx.perception.filter().relevant(t);
    }

    // ------------------------------------------------------------------------------------------------ emergency

    /**
     * Runs every tick before the brain thinks (BaseBrain). Clutches when going over an edge and the calm heal after
     * a fight take over the inputs; returns true then and the brain skips its think() for this tick.
     */
    public boolean emergencyTick() {
        boolean inGame = ctx.bot.brain() instanceof net.h2020.bots.ai.brain.GameBrain;
        if (!inGame) return false;
        CombatStats.tick(engaged());
        if (kbTick >= 0 && ctx.tick - kbTick >= 10) {
            Location me = ctx.pos();
            if (kbFrom != null && me.getWorld() == kbFrom.getWorld()) {
                double push = (me.getX() - kbFrom.getX()) * kbUx + (me.getZ() - kbFrom.getZ()) * kbUz;
                CombatStats.inc("kb.n");
                CombatStats.add("kb.sum", push);
                if (kbJumped) { CombatStats.inc("kb.jr.n"); CombatStats.add("kb.jr.sum", push); }
            }
            kbTick = -1;
        }
        if (clutch.tick()) {
            if (tactics.busy()) tactics.cancel();
            return true;
        }
        if (CombatStats.v2 && !engaged()) return items.calmTick();
        return false;
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
        if (CombatStats.v2) { v2Tick(me, tl, d, stalledFor, noHitsFor); return; }
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
                CombatStats.inc("gapple");
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

    // ------------------------------------------------------------------------------------------------ v2

    private void v2Tick(Location me, Location tl, double d, long stalledFor, long noHitsFor) {
        if (stalledFor > 200 && noHitsFor > 200) {
            // unreachable: a pearl across the gap first, if we have one
            if (tryEngagePearl(tl, d)) return;
            giveUp();
            return;
        }
        boolean los = los(tl);
        double hp = ctx.health();
        if (ctx.tick - gearTick > 40) {
            gearTick = ctx.tick;
            gearDiff = gear(target) - gear(ctx.player);
        }
        if (ctx.tick % 3 == 0) edgeBehind = edgeBehind(me, tl);

        // blocks: towering / walls / cutting in progress
        if (tactics.busy()) {
            if (tactics.tick()) return;
        }
        // items: potions, gapples, lava, fire, TNT
        items.allowGapple = allowGapple;
        items.allowTnt = ctx.bot.brain() instanceof net.h2020.bots.ai.brain.BedwarsBrain;
        boolean meleeBusy = d < 3.2 && ctx.tick - Math.max(lastHitLanded, lastHurt) < 15;
        if (items.fightTick(target, d, lastHurt, meleeBusy)) {
            if (items.eating()) {
                if (d < 6) backOff(tl, d);
                ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            }
            return;
        }
        boolean losing = hp <= ctx.persona.retreatHealth + 2 && target.getHealth() > hp + 3;
        if (tactics.consider(target, d, losing || (retreating && hp <= 8))) return;

        // on top of a tower we just built: hold it (bow / throws / hit them if they pillar up next to us)
        if (tactics.holdingHigh()) {
            ctx.motor.sneak(true); // (sneaking: never steps off the tower)
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            if (los && ranged(d, tl)) return;
            if (d < clickRange()) { selectWeapon(); ctx.motor.holdLeft(cpsNow); }
            return;
        }

        // retreat when losing
        retreating = hp <= ctx.persona.retreatHealth && target.getHealth() > hp + 3 && d < 12;
        if (retreating && ctx.persona.skill.aggression < 0.85) {
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            if (allowPearl && !pearlUsed && hp <= 5 && d < 6 && Inv.findHotbar(ctx.player, Inv.PEARL) >= 0
                    && ctx.tick % 10 == 0 && ctx.rng.nextDouble() < 0.15 + 0.35 * ctx.persona.skill.overall) {
                pearlEscapeTicks = 30;
            }
            if (pearlEscapeTicks > 0) {
                pearlEscapeTicks--;
                if (escapePearl(tl)) return;
            }
            backOff(tl, d);
            if (d < 3.2) { selectWeapon(); ctx.motor.holdLeft(cpsNow); }
            return;
        }

        // ranged options
        if (los && ranged(d, tl)) return;
        drawTicks = 0;

        // chase when far / unreachable directly
        if (d > 6.5 || Math.abs(tl.getY() - me.getY()) > 2.5 || (stalledFor > 50 && (d > clickRange() || noHitsFor > 60))) {
            if (allowChase) {
                ctx.nav.follow(tl, 1.8, chaseOptions);
                ctx.nav.tick();
                if (ctx.nav.failed()) {
                    if (tryEngagePearl(tl, d)) return;
                    giveUp();
                    return;
                }
            }
            if (los && d < 14 && (!ctx.nav.doingAction() || d < 4.5)) ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            if (!ctx.nav.doingAction()) {
                if (d < clickRange()) ctx.motor.holdLeft(cpsNow);
                selectWeapon();
            }
            return;
        }
        if (ctx.nav.active()) ctx.nav.stop();
        meleeV2(tl, d);
    }

    private int pearlEscapeTicks;
    private Vector nearVoid;
    private long nearTick = -100;

    /** Gear score: armor points + weapon value. */
    private static double gear(Player p) { return Inv.totalArmor(p) + Inv.bestMeleeValue(p); }

    private void selectWeapon() {
        int slot = Inv.bestMeleeHotbar(ctx.player);
        if (slot >= 0) {
            if (ctx.c.heldSlot() != slot) {
                ctx.c.selectHotbar(slot);
                ItemStack held = ctx.player.getItemInHand();
                if (!Items.isSword(held) && !Items.isAxe(held)) CombatStats.inc("weaponswap");
            }
        } else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
    }

    /** Void within ~3 blocks in the direction we'd be knocked (away from the target). */
    private boolean edgeBehind(Location me, Location tl) {
        double dx = me.getX() - tl.getX(), dz = me.getZ() - tl.getZ();
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3) return false;
        dx /= l; dz /= l;
        WorldView wv = ctx.world();
        int y = floor(me.getY() + 0.01);
        for (double r = 1.0; r <= 3.01; r += 1.0) {
            for (double side = -0.5; side <= 0.51; side += 0.5) {
                int x = floor(me.getX() + dx * r - dz * side), z = floor(me.getZ() + dz * r + dx * side);
                if (wv.passable(x, y, z) && wv.passable(x, y - 1, z) && wv.voidBelow(x, y, z, 30)) return true;
            }
        }
        return false;
    }

    /** Average horizontal direction from a spot toward adjacent void within 3 blocks (null if none). */
    private Vector voidAround(Location at) {
        WorldView wv = ctx.world();
        int x = floor(at.getX()), y = floor(at.getY() + 0.01), z = floor(at.getZ());
        double vx = 0, vz = 0;
        int n = 0;
        for (int r = 1; r <= 3; r++) {
            int[][] dirs = {{r, 0}, {-r, 0}, {0, r}, {0, -r}, {r, r}, {-r, r}, {r, -r}, {-r, -r}};
            for (int[] dd : dirs) {
                if (wv.passable(x + dd[0], y, z + dd[1]) && wv.voidBelow(x + dd[0], y, z + dd[1], 30)) {
                    vx += dd[0] / (double) (r * r); vz += dd[1] / (double) (r * r); n++;
                }
            }
        }
        if (n == 0) return null;
        double l = Math.sqrt(vx * vx + vz * vz);
        if (l < 1e-3) return null;
        return new Vector(vx / l, 0, vz / l);
    }

    private void meleeV2(Location tl, double d) {
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        selectWeapon();
        ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);

        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / len, uz = dz / len;
        boolean ground = ctx.c.onGround();

        // jump reset: jump on the tick the hit's velocity arrives (sprinting into them: the jump boost eats KB)
        boolean jumpedNow = false;
        if (ctx.c.tookKnockback() && ctx.tick - lastHurt <= 2 && ground && d < 4.2) {
            if (ctx.rng.nextDouble() < style.jumpReset && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
                ctx.motor.jump(true);
                jumpedNow = true;
                if (kbTick >= 0) kbJumped = true;
                CombatStats.inc("jumpreset");
            }
        }

        boolean comboing = comboCount >= 2 && ctx.tick - lastHitLanded < 14;
        boolean beingComboed = hitsTaken >= 2 && ctx.tick - lastHurt < 16;
        if (comboing && style.comboLock) CombatStats.add("combolock.ticks", 1.0 / 20);

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
        boolean strafe = ctx.persona.strafes && !onBridge && d < 4.2;
        // strafe direction: timed switches (irregular for good players), a switch when comboed, and never toward the void
        if (ctx.tick >= nextStrafeSwitch) {
            if (ctx.rng.nextDouble() < 0.8) { strafeDir = -strafeDir; CombatStats.inc("strafeswitch"); }
            double ms = ctx.persona.strafeSwitchMs * (0.6 + 0.8 * ctx.rng.nextDouble());
            // sweats throw in a quick double switch now and then
            if (ctx.rng.nextDouble() < 0.25 * style.strafeSkill) ms = 120 + ctx.rng.nextDouble() * 150;
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ms);
        }
        if (breakOut) {
            breakOut = false;
            if (ctx.rng.nextDouble() < 0.3 + 0.6 * style.strafeSkill) {
                strafeDir = -strafeDir;
                nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
                CombatStats.inc("comboescape");
            }
        }
        if (ctx.tick - nearTick >= 5) { nearTick = ctx.tick; nearVoid = style.edgeSense > 0.2 ? voidAround(me) : null; }
        Vector near = nearVoid;
        if (near != null && ctx.rng.nextDouble() < style.edgeSense) {
            // tangent that moves us away from the void side (so the knockback direction rotates off the edge)
            double t1x = -uz, t1z = ux; // strafeDir = +1
            double dot = t1x * near.getX() + t1z * near.getZ();
            int want = dot > 0 ? -1 : 1;
            if (want != strafeDir && Math.abs(dot) > 0.2) { strafeDir = want; CombatStats.inc("edgestrafe"); }
            if (edgeBehind) strafe = !onBridge; // circle out of it even on islands where we'd otherwise hold W
        }
        if (comboing && style.comboLock) strafe = strafe && d > 3.2 && ctx.rng.nextDouble() < 0.3; // straight in, keep them stuck

        double fwd = 1;
        if (d < 1.7) fwd = 0;
        if (d < 1.0) fwd = edgeBehind ? 0 : -1;
        if (wtapTicks > 0) { wtapTicks--; fwd = sTapNow && !edgeBehind ? -1 : 0; }
        if (runTicks > 0) {
            runTicks--;
            fwd = edgeBehind ? 0 : -1;
            strafe = !onBridge;
        }
        if (jumpedNow) fwd = 1; // the jump reset only works sprinting into them

        double sx = 0, sz = 0;
        if (strafe) { sx = -uz * strafeDir; sz = ux * strafeDir; }
        double wx = mx * fwd + sx * 0.9, wz = mz * fwd + sz * 0.9;
        if (!safe(me, wx, wz)) {
            double ax = mx * fwd - sx * 0.9, az = mz * fwd - sz * 0.9;
            if (strafe && safe(me, ax, az)) { strafeDir = -strafeDir; wx = ax; wz = az; }
            else if (safe(me, mx * fwd, mz * fwd)) { wx = mx * fwd; wz = mz * fwd; }
            else if (safe(me, mx, mz)) { wx = mx; wz = mz; }
            else { wx = 0; wz = 0; ctx.motor.sneak(onBridge || edgeBehind); }
            CombatStats.inc("edgestop");
        }
        if (Math.abs(wx) + Math.abs(wz) > 1e-3) ctx.motor.moveToward(wx, wz);
        ctx.motor.sprint(fwd > 0 && wtapTicks == 0);

        // jump crits / sprint-jump in (not with the void behind: airborne = more knockback)
        if (ground && critCooldown == 0 && !jumpedNow) {
            boolean crit = d < 3.1 && ctx.tick - lastHurt > 10 && !edgeBehind && ctx.rng.nextDouble() < ctx.persona.jumpCritChance * 0.25;
            boolean closeIn = d > 4.5 && ctx.persona.sprintJumper && ctx.rng.nextDouble() < 0.15;
            if (!crit && !closeIn && ctx.persona.skill.overall < 0.35 && ctx.rng.nextDouble() < 0.06) crit = true;
            if ((crit || closeIn) && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
                ctx.motor.jump(true);
                critCooldown = AIMath.range(ctx.rng, 10, 24);
            }
        }

        if (blockHitTicks > 0) {
            blockHitTicks--;
            if (Inv.holding(ctx.player, Inv.SWORD)) ctx.motor.holdRight();
        } else if (d < clickRange()) {
            ctx.motor.holdLeft(cpsNow);
        }

        // being comboed: rod them to reset it
        if (beingComboed && style.rodReset && allowRod && rodCooldown == 0 && d > 2.0 && d < 4.5 && Inv.findHotbar(ctx.player, Inv.ROD) >= 0
                && ctx.rng.nextDouble() < 0.3) {
            castRod(tl, d, "rod.reset");
        }
    }

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
            if (ctx.rng.nextDouble() < 0.8) { strafeDir = -strafeDir; CombatStats.inc("strafeswitch"); }
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

    /** Is moving in world direction (x,z) safe from walking off into the void (v2: or into lava / fire)? */
    private boolean safe(Location me, double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-3) return true;
        WorldView wv = ctx.world();
        double ahead = 0.9;
        int cx = floor(me.getX() + x / len * ahead), cz = floor(me.getZ() + z / len * ahead), cy = floor(me.getY() + 0.01);
        if (CombatStats.v2) {
            byte f = wv.shape(cx, cy, cz), b = wv.shape(cx, cy - 1, cz);
            if (f == Shape.LAVA || b == Shape.LAVA || wv.type(cx, cy, cz) == Material.FIRE) return false;
            // moving fast (sprinting, knocked): look a bit further ahead too
            Vector v = ctx.c.velocity();
            double sp = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
            if (sp > 0.2) {
                int fx = floor(me.getX() + x / len * 1.7), fz = floor(me.getZ() + z / len * 1.7);
                if ((fx != cx || fz != cz) && !wv.standable(fx, cy, fz) && !wv.standable(fx, cy - 1, fz) && wv.passable(fx, cy, fz)
                        && wv.voidBelow(fx, cy, fz, 30) && !(wv.standable(cx, cy, cz) || wv.standable(cx, cy - 1, cz))) return false;
            }
        }
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
        if (d > 5 && !eating()) {
            ctx.motor.aimRotation(Motor.P_COMBAT + 1, AIMath.yawOf(wx, wz), 10, AimIntent.NAVIGATE, 8);
            ctx.motor.sprint(true);
        }
    }

    // ------------------------------------------------------------------------------------------------ ranged

    /** v2: select the rod now, cast on a following tick once it is in the hand (no same-tick swap-and-click). */
    private void castRod(Location tl, double d, String stat) {
        int slot = Inv.findHotbar(ctx.player, Inv.ROD);
        if (slot < 0) return;
        if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        rodWant = AIMath.range(ctx.rng, 1, 3);
        rodStat = stat;
    }

    private boolean rodStep(Location tl, double d) {
        rodWant--;
        int slot = Inv.findHotbar(ctx.player, Inv.ROD);
        if (slot < 0) { rodWant = 0; return false; }
        if (ctx.c.heldSlot() != slot) { ctx.c.selectHotbar(slot); return true; }
        Location aim = Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.HOOK, style.leadSkill,
                style.projErrDeg * 1.3, shot());
        ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 1.4, 0.04), AimIntent.COMBAT, 0.3);
        if (rodWant <= 0) {
            ctx.c.rightClick();
            rodWant = 0;
            rodTicks = AIMath.range(ctx.rng, 5, 10);
            CombatStats.inc(rodStat);
        }
        return true;
    }

    private Vector vel() {
        Sighting s = ctx.perception.get(target);
        return s != null ? s.lastVel : new Vector();
    }

    private boolean ranged(double d, Location tl) {
        boolean v2 = CombatStats.v2;
        if (v2 && rodWant > 0 && rodStep(tl, d)) return true;
        // fishing rod: cast as they come into range (pulls them in / breaks their sprint), then swap back to the sword
        // (1.8 drops the hook when the rod leaves the hand). Only from the hotbar: no inventory clicks mid-fight.
        if (rodTicks > 0) {
            rodTicks--;
            ctx.motor.aimEntity(Motor.P_COMBAT, target, AimIntent.COMBAT);
            Location me = ctx.pos();
            double ax = tl.getX() - me.getX(), az = tl.getZ() - me.getZ();
            if (d > 2.6 && safe(me, ax, az)) { ctx.motor.moveToward(ax, az); ctx.motor.sprint(true); }
            if (rodTicks == 0) {
                if (v2) selectWeapon(); else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
                rodCooldown = AIMath.range(ctx.rng, 25, 80);
            }
            return true;
        }
        if (allowRod && ctx.persona.rodder && rodCooldown == 0 && d > 3.4 && d < 7.5 && Inv.findHotbar(ctx.player, Inv.ROD) >= 0
                && ctx.rng.nextDouble() < 0.08) {
            if (v2) { castRod(tl, d, "rod"); return true; }
            if (Inv.equip(ctx.player, ctx.c, Inv.ROD, 1) && Inv.holding(ctx.player, Inv.ROD)) {
                ctx.motor.aimPoint(Motor.P_COMBAT, lead(tl, d, 1.4, 0.04), AimIntent.COMBAT, 0.3);
                ctx.c.rightClick();
                rodTicks = AIMath.range(ctx.rng, 5, 10);
                CombatStats.inc("rod");
            } else rodCooldown = 4; // selected this tick: cast next time it comes up
            return true;
        }
        Vector edge = voidDirection(tl);
        // fireball at bridgers / people on edges (Bed Wars)
        if (allowFireball && fireballCooldown == 0 && d > 6 && d < 26 && edge != null
                && Inv.has(ctx.player, Inv.FIREBALL) && ctx.rng.nextDouble() < 0.08) {
            if (v2 ? Inv.findHotbar(ctx.player, Inv.FIREBALL) >= 0 && Inv.equip(ctx.player, ctx.c, Inv.FIREBALL, 6)
                    : Inv.equip(ctx.player, ctx.c, Inv.FIREBALL, 6)) {
                Location at = tl.clone().add(0, 0.4, 0);
                if (v2) {
                    // straight flight (~0.9 b/t): lead them a bit, aim at the feet / the bridge under them
                    double t = d / 0.9;
                    Vector v = vel();
                    at = tl.clone().add(v.getX() * t * style.leadSkill, 0.2, v.getZ() * t * style.leadSkill);
                    double e = Math.toRadians(style.projErrDeg) * d;
                    at.add(ctx.rng.nextGaussian() * e, ctx.rng.nextGaussian() * e * 0.5, ctx.rng.nextGaussian() * e);
                }
                ctx.motor.aimPoint(Motor.P_COMBAT, at, AimIntent.COMBAT, 0.3);
                if (ctx.c.aimSettled()) { ctx.c.rightClick(); fireballCooldown = 50; CombatStats.inc("fireball"); }
            }
            return true;
        }
        // snowballs / eggs for void knockback
        boolean throwHot = Inv.findHotbar(ctx.player, Inv.THROWABLE) >= 0;
        if (allowThrowables && (v2 ? throwHot && throwsLeft > 0 : throwsLeft > 0 && Inv.has(ctx.player, Inv.THROWABLE))
                && d > 3.5 && d < 22 && edge != null) {
            if (Inv.equip(ctx.player, ctx.c, Inv.THROWABLE, 5)) {
                Location aim = v2 ? Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.THROWABLE, style.leadSkill, style.projErrDeg, shot()) : null;
                ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                if (rangedCooldown == 0 && ctx.c.aimSettled()) {
                    ctx.c.rightClick();
                    throwsLeft--;
                    rangedCooldown = v2 ? AIMath.range(ctx.rng, style.throwGap, style.throwGap + 4) : AIMath.range(ctx.rng, 4, 9);
                    CombatStats.inc("throw");
                }
            }
            standOrStrafe();
            return true;
        }
        // engage pearl (tryhards)
        if (allowPearl && !pearlUsed && ctx.persona.tryhard && d > 18 && d < 32 && Inv.has(ctx.player, Inv.PEARL)
                && ctx.rng.nextDouble() < 0.01) {
            if (v2) { if (tryEngagePearl(tl, d)) return true; }
            else {
                if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                    Location land = tl.clone().add(ctx.pos().toVector().subtract(tl.toVector()).normalize().multiply(2.5));
                    ctx.motor.aimPoint(Motor.P_COMBAT, lead(land, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                    if (ctx.c.aimSettled()) { ctx.c.rightClick(); pearlUsed = true; CombatStats.inc("pearl.engage"); }
                }
                return true;
            }
        }
        // bow
        boolean hasBow = v2 ? Inv.findHotbar(ctx.player, Inv.BOW) >= 0 && Inv.has(ctx.player, Inv.ARROW)
                : Inv.has(ctx.player, Inv.BOW) && Inv.has(ctx.player, Inv.ARROW);
        boolean punch = false;
        boolean wantBow = usingBowThisFight;
        if (v2 && hasBow && allowBow) {
            int bs = Inv.findHotbar(ctx.player, Inv.BOW);
            ItemStack bow = bs >= 0 ? ctx.player.getInventory().getItem(bs) : null;
            punch = bow != null && bow.getEnchantmentLevel(Enchantment.ARROW_KNOCKBACK) > 0;
            // knock bridgers / edge-standers off, or kite someone with better gear
            if (edge != null && (punch || ctx.persona.skill.overall > 0.4)) wantBow = true;
            if (style.kiter && gearDiff > 4) wantBow = true;
        }
        double minBow = v2 && (punch || (style.kiter && gearDiff > 4)) ? 7 : 9;
        boolean bowRange = d > minBow && d < 45;
        if (allowBow && wantBow && (bowRange || drawTicks > 0) && hasBow) {
            if (d < 6) { drawTicks = 0; return false; } // they closed in: switch to sword
            if (!Inv.equip(ctx.player, ctx.c, Inv.BOW, 2)) return true;
            if (drawTicks == 0) {
                if (rangedCooldown > 0) { standOrStrafe(); return true; }
                drawTarget = AIMath.range(ctx.rng, 15, 24);
                if (v2 && d > 25) drawTarget = AIMath.range(ctx.rng, 20, 26); // full draw for long shots
            }
            Location aim = null;
            if (v2) aim = Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.arrow(Math.max(drawTicks + 1, drawTarget)),
                    style.leadSkill, style.projErrDeg * 0.5, shot());
            ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 2.9, 0.05), AimIntent.COMBAT, 0.2);
            drawTicks++;
            if (drawTicks < drawTarget || !ctx.c.aimSettled()) {
                ctx.motor.holdRight();
                if (drawTicks > 60) { drawTicks = 0; rangedCooldown = 10; }
            } else {
                // release (not holding right this tick)
                drawTicks = 0;
                rangedCooldown = AIMath.range(ctx.rng, 6, 14);
                CombatStats.inc(punch ? "bow.punch" : "bow");
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

    /**
     * Pearl to the far side of a gap when the target can't be reached on foot (v2). Lands ~2.5 blocks short of them on
     * ground they stand on. @return true while doing it.
     */
    private boolean tryEngagePearl(Location tl, double d) {
        if (!allowPearl || pearlUsed || d < 8 || d > 34 || Inv.findHotbar(ctx.player, Inv.PEARL) < 0) return false;
        if (ctx.health() < 10 || !los(tl)) return false;
        WorldView wv = ctx.world();
        Vector back = ctx.pos().toVector().subtract(tl.toVector()).setY(0);
        if (back.lengthSquared() < 1e-3) return false;
        back.normalize();
        for (double off = 2.5; off >= 0.5; off -= 1.0) {
            int x = floor(tl.getX() + back.getX() * off), z = floor(tl.getZ() + back.getZ() * off);
            int land = wv.landing(x, floor(tl.getY()) + 2, z, 5);
            if (land == Integer.MIN_VALUE) continue;
            if (!Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) return true;
            Location spot = new Location(tl.getWorld(), x + 0.5, land + 0.1, z + 0.5);
            Location aim = Ballistics.aim(ctx.eye(), spot, new Vector(), Ballistics.PEARL, 1, style.projErrDeg, shot());
            if (aim == null) return false;
            ctx.motor.aimPoint(Motor.P_COMBAT, aim, AimIntent.COMBAT, 0.5);
            if (ctx.c.aimSettled() || Ballistics.aimErr(ctx, aim) < 2.0) {
                ctx.c.rightClick();
                pearlUsed = true;
                CombatStats.inc("pearl.engage");
                bestDistTick = ctx.tick; // give the fight another go from over there
            }
            return true;
        }
        return false;
    }

    /** @return true while aiming/throwing (v1 always returns false after acting, as before). */
    private boolean escapePearl(Location tl) {
        Location me = ctx.pos();
        Vector away = me.toVector().subtract(tl.toVector()).setY(0);
        if (away.lengthSquared() < 1e-3) return false;
        away.normalize();
        // find a safe landing ~12 blocks away
        WorldView wv = ctx.world();
        boolean v2 = CombatStats.v2;
        for (int dist = 14; dist >= 6; dist -= 2) {
            for (int turn = 0; turn < (v2 ? 3 : 1); turn++) {
                // (v2: also try a bit to the left/right of straight away)
                double ang = turn == 0 ? 0 : (turn == 1 ? 0.6 : -0.6);
                double ax = away.getX() * Math.cos(ang) - away.getZ() * Math.sin(ang), az = away.getX() * Math.sin(ang) + away.getZ() * Math.cos(ang);
                int x = floor(me.getX() + ax * dist), z = floor(me.getZ() + az * dist);
                int land = wv.landing(x, floor(me.getY()) + 3, z, 8);
                if (land == Integer.MIN_VALUE) continue;
                if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                    Location tgt = new Location(me.getWorld(), x + 0.5, land, z + 0.5);
                    Location aim = v2 ? Ballistics.aim(ctx.eye(), tgt, new Vector(), Ballistics.PEARL, 1, style.projErrDeg, shot()) : null;
                    Location ap = aim != null ? aim : lead(tgt, dist, 1.5, 0.03);
                    ctx.motor.aimPoint(Motor.P_COMBAT + 2, ap, AimIntent.FLICK, v2 ? 0.6 : 0.3);
                    if (ctx.c.aimSettled() || (v2 && Ballistics.aimErr(ctx, ap) < 3.0)) {
                        ctx.c.rightClick();
                        pearlUsed = true;
                        pearlEscapeTicks = 0;
                        CombatStats.inc("pearl.escape");
                    }
                }
                return v2;
            }
        }
        return false;
    }

    private boolean los(Location tl) {
        if (ctx.tick - losTick >= 5) {
            int r = Raycast.lineOfSight(ctx.player.getWorld(), ctx.eye(), tl.clone().add(0, 1.4, 0));
            if (r != Raycast.UNKNOWN) losCache = r;
            losTick = ctx.tick;
        }
        return losCache == Raycast.CLEAR;
    }

    public boolean holdingMaterial(Material m) {
        return ctx.player.getItemInHand() != null && ctx.player.getItemInHand().getType() == m;
    }

    /** Error sample that stays the same for ~1.2 s (one throw / one draw), so the crosshair has a fixed place to go. */
    private final long shotSalt = new java.util.Random().nextLong();
    private java.util.Random shot() { return new java.util.Random(shotSalt ^ (ctx.tick / 25) * 0x9E3779B97F4A7C15L); }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
