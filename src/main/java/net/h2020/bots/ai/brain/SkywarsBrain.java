package net.h2020.bots.ai.brain;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.game.GameState;
import net.h2020.api.game.SkywarsView;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Looter;
import net.h2020.bots.ai.inv.Organizer;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SkyWars: cage idling -> drop -> loot own island (armor, sword, blocks, pearls, gapples, bow...) -> equip and sort
 * the hotbar -> decide by utility: bridge to mid, bridge to a neighbour island, or camp the
 * island edge -> loot mid / hunt the remaining players. Fights interrupt everything; snowballs/eggs/bow knock
 * bridgers into the void.
 */
public final class SkywarsBrain extends GameBrain {
    private enum St { CAGE, DROP, LOOT, ORGANIZE, GATHER, DECIDE, TRAVEL, CAMP, HUNT, END }

    private final SkywarsView view;
    private St st = St.CAGE;
    private long stTick;
    private int island = -1;
    private final Set<Location> looted = new HashSet<>();
    /** Chests we could not get to / open, with the tick until which they are skipped. */
    private final java.util.Map<Location, Long> unreachable = new java.util.HashMap<>();
    private List<Block> lootQueue = new ArrayList<>();
    /** Chests with no walking route: the second try may dig. */
    private final Set<Location> digFor = new HashSet<>();
    private Looter looter;
    private Organizer organizer;
    private BlockBreaker miner;
    private Location travelTo;
    private boolean travelLootMid;
    private int travelIsland = -1;
    private NavOptions travelOpts;
    private double travelRadius;
    private long campUntil;
    private int jumpTimer;
    private long nextDecision;

    public SkywarsBrain(SkywarsView view) {
        super(view);
        this.view = view;
    }

    @Override
    protected void onGameStart() {
        looter = new Looter(ctx);
        organizer = new Organizer(ctx);
        miner = new BlockBreaker(ctx);
        island = view.islandOf(ctx.player);
        ctx.combat.allowThrowables = true;
        ctx.combat.allowBow = true;
        ctx.combat.allowGapple = true;
        ctx.combat.allowPearl = ctx.persona.tryhard;
        ctx.combat.leash = 45;
        ctx.combat.chaseOptions = net.h2020.bots.ai.combat.Combat.chase(NavOptions.bridging(10));
        ctx.combat.chaseOptions.maxNodes = 4000;
    }

    private void set(St s) { st = s; stTick = ctx.tick; pendingAct = null; }

    // ---- human pacing: a look before committing to a plan, a breather after a chest ----
    private Runnable pendingAct;
    private long pendingUntil;
    private Location pendingLook;
    private long lootPauseUntil;
    private int crouchSpam;

    /**
     * Run a decision now, or first stand for a moment looking where we are about to go (players eye up the mid or
     * the next island before they commit; sweats hesitate less).
     */
    private void commit(Runnable act, Location lookAt) {
        double o = ctx.persona.skill.overall;
        if (lookAt != null && chance(0.45 - 0.3 * o)) {
            pendingAct = act;
            pendingLook = lookAt.clone().add(0, 1.2, 0);
            pendingUntil = ctx.tick + AIMath.range(ctx.rng, 4, 8 + (int) (12 * (1 - o)));
            return;
        }
        act.run();
    }

    @Override
    public String toString() {
        if (ctx == null) return "SkywarsBrain[" + st + "]";
        org.bukkit.Location l = ctx.pos();
        String fight = ctx.combat.engaged() ? " vs " + ctx.combat.target().getName() : "";
        return "SkywarsBrain[" + st + fight + "]{" + ctx.nav.describe() + String.format(" @%.1f,%.1f,%.1f}", l.getX(), l.getY(), l.getZ());
    }

    private long inState() { return ctx.tick - stTick; }

    @Override
    protected void think() {
        pollState();
        if (island < 0) island = view.islandOf(ctx.player);
        GameState gs = game.state();
        if (gs == GameState.ENDING) { set(St.END); endTick(); return; }
        if (!view.cagesOpen() || gs == GameState.WAITING || gs == GameState.STARTING) { cageTick(); return; }
        if (st == St.CAGE) { set(St.DROP); openTick = ctx.tick; }

        // fights interrupt everything except a GUI in progress
        boolean busyGui = looter.active() && ctx.c.containerOpen();
        if (!busyGui && st != St.DROP && combatPolicy(st == St.CAMP || st == St.HUNT ? 14 : 9)) {
            if (looter.active()) {
                // the chest we were at is still to do after the fight
                if (st == St.LOOT && looter.chest() != null) lootQueue.add(0, looter.chest());
                looter.cancel();
            }
            if (st == St.ORGANIZE) organizer.cancel();
            ctx.combat.tick();
            return;
        }

        switch (st) {
            case DROP:
                if (ctx.c.onGround() && inState() > 4) { buildLootQueue(island); set(St.LOOT); }
                break;
            case LOOT: lootTick(); break;
            case ORGANIZE:
                if (!organizer.tick() || inState() > 200) { organizer.cancel(); set(St.DECIDE); }
                break;
            case GATHER: gatherTick(); break;
            case DECIDE: decide(); break;
            case TRAVEL: travelTick(); break;
            case CAMP: campTick(); break;
            case HUNT: huntTick(); break;
            default: set(St.DECIDE);
        }
    }

    private boolean chance(double p) { return ctx.rng.nextDouble() < p; }

    // ------------------------------------------------------------------------------------------------ cage

    private void cageTick() {
        st = St.CAGE;
        // jump around in the cage, look around (gaze handles the looking)
        if (--jumpTimer <= 0) {
            if (chance(0.35)) ctx.motor.jump(true);
            jumpTimer = AIMath.range(ctx.rng, 4, chance(0.2) ? 6 : 50);
        }
        if (crouchSpam > 0) {
            // crouch spam at the people in the other cages
            crouchSpam--;
            ctx.motor.sneak(crouchSpam % 4 < 2);
        } else if (chance(0.002 + 0.004 * ctx.persona.skill.chattiness)) {
            crouchSpam = AIMath.range(ctx.rng, 6, 22);
        } else if (chance(0.004)) ctx.motor.sneak(true);
        if (chance(0.01)) ctx.c.selectHotbar(ctx.rng.nextInt(9));
        else if (chance(0.006)) {
            // scroll through the hotbar a few slots
            int dir = chance(0.5) ? 1 : 8;
            ctx.c.selectHotbar((ctx.c.heldSlot() + dir) % 9);
        }
    }

    // ------------------------------------------------------------------------------------------------ loot

    private void buildLootQueue(int isl) {
        lootQueue = new ArrayList<>();
        List<Location> chests = isl >= 0 ? view.islandChests(isl) : new ArrayList<Location>();
        for (Location l : chests) {
            Block b = l.getBlock();
            if ((b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) && !skipUnreachable(b)) lootQueue.add(b);
        }
        sortForLooting(lootQueue);
    }

    /**
     * Island chests in the order a player grabs them: the ones on the level we landed on first (nearest first), the
     * one down the ladder / in the cave last, instead of hopping down and back up between them.
     */
    private void sortForLooting(List<Block> list) {
        final Location me = ctx.pos();
        java.util.Collections.sort(list, new java.util.Comparator<Block>() {
            public int compare(Block a, Block b) { return Double.compare(cost(a), cost(b)); }
            private double cost(Block c) {
                double dy = Math.abs(c.getY() + 1 - me.getY());
                return Math.sqrt(AIMath.hDistSq(c.getX() + 0.5, c.getZ() + 0.5, me.getX(), me.getZ())) + (dy > 1.5 ? 6 + dy * 2 : dy);
            }
        });
    }

    /**
     * Where "going mid" leads: the nearest mid chest worth opening. Maps whose "mid" is a ring or several hubs
     * (Onionring, Fossil) spread their mid chests far apart; heading for the one map point would send all twelve
     * players to the same spot on one side.
     */
    private Location midGoal(Location me) {
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location l : view.midChests()) {
            if (l.getWorld() != me.getWorld()) continue;
            Block b = l.getBlock();
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            if (skipUnreachable(b) || (looted.contains(b.getLocation()) && !refilledSince(b))) continue;
            double d = l.distanceSquared(me);
            if (d < bd) { bd = d; best = l; }
        }
        Location m = view.mid();
        if (best == null) return m;
        // a chest right next to the centre: just go to the centre (the classic "mid")
        if (m != null && m.getWorld() == best.getWorld() && AIMath.hDist(m, best) < 20) return m;
        return best.clone().add(0.5, 1, 0.5);
    }

    private void buildMidQueue() {
        lootQueue = new ArrayList<>();
        Location me = ctx.pos();
        for (Location l : view.midChests()) {
            // only the chests of the mid area we are in (ring / hub maps spread them over the whole map)
            if (l.getWorld() != me.getWorld() || AIMath.hDist(l, me) > 28) continue;
            Block b = l.getBlock();
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            if (skipUnreachable(b)) continue;
            if (looted.contains(b.getLocation()) && !refilledSince(b)) continue;
            if (b.getState() instanceof Chest && view.chestLooted((Chest) b.getState()) && chance(0.6)) continue;
            lootQueue.add(b);
        }
        sortByDistance(lootQueue);
    }

    /**
     * A chest this bot looted counts as refilled once nobody has opened it since the last refill (SkywarsView resets
     * chestLooted on refill; the bot's own opening set it). Forget it from the looted memory so it is visited again.
     */
    private boolean refilledSince(Block b) {
        if (!(b.getState() instanceof Chest)) return false;
        if (view.chestLooted((Chest) b.getState())) return false;
        looted.remove(b.getLocation());
        return true;
    }

    private boolean skipUnreachable(Block b) {
        Long until = unreachable.get(b.getLocation());
        return until != null && ctx.tick < until;
    }

    private void sortByDistance(List<Block> list) {
        final Location me = ctx.pos();
        java.util.Collections.sort(list, new java.util.Comparator<Block>() {
            public int compare(Block a, Block b) {
                return Double.compare(a.getLocation().distanceSquared(me), b.getLocation().distanceSquared(me));
            }
        });
    }

    private void lootTick() {
        if (ctx.tick < lootPauseUntil) return; // standing for a second after a chest: the gaze looks around
        if (!looter.active()) {
            while (!lootQueue.isEmpty() && looted.contains(lootQueue.get(0).getLocation())) lootQueue.remove(0);
            if (lootQueue.isEmpty()) {
                organizer.reset();
                set(ctx.persona.skill.overall < 0.2 && chance(0.5) ? St.DECIDE : St.ORGANIZE);
                return;
            }
            Block next = lootQueue.remove(0);
            NavOptions lo = NavOptions.walk();
            // walk (stairs, ladders) to island chests like a player; dig only when there is no walking route at all
            lo.breakBlocks = digFor.contains(next.getLocation());
            looter.start(next, lo);
        }
        Looter.Result r = looter.tick();
        if (r != Looter.Result.RUNNING && looter.chest() != null) {
            Location cl = looter.chest().getLocation();
            if (r == Looter.Result.FAILED && looter.noRoute() && digFor.add(cl)) {
                lootQueue.add(looter.chest()); // no walking route: come back to it last and dig in
                return;
            }
            looted.add(cl);
            // never opened it, so it still counts as "not looted since the refill": without this the mid queue
            // offers the same unreachable chest again and again
            if (r == Looter.Result.FAILED) unreachable.put(looter.chest().getLocation(), ctx.tick + 20 * 90);
            else if (chance(0.2 - 0.15 * ctx.persona.skill.overall)) lootPauseUntil = ctx.tick + AIMath.range(ctx.rng, 3, 10);
        }
        if (inState() > 20 * 60) { looter.cancel(); set(St.ORGANIZE); }
    }

    // ------------------------------------------------------------------------------------------------ gathering

    private Block gatherTarget;
    private Location gatherDrop;
    private long gatherDropTick, gatherStart;
    private int gatherBlocked;

    /** Break island blocks that drop something placeable (what players do when they run out), then walk over the drop. */
    private void gatherTick() {
        if (Inv.count(ctx.player, Inv.BLOCK) >= 20 || inState() > 20 * 20) { ctx.nav.stop(); gatherTarget = null; gatherDrop = null; set(St.DECIDE); return; }
        if (gatherDrop != null) {
            ctx.nav.goTo(gatherDrop, 0.7, NavOptions.walk());
            ctx.nav.tick();
            if (ctx.nav.arrived() || ctx.nav.failed() || ctx.tick - gatherDropTick > 60) { ctx.nav.stop(); gatherDrop = null; }
            return;
        }
        if (gatherTarget == null) {
            gatherTarget = findGatherBlock(6);
            if (gatherTarget == null) { campUntil = ctx.tick + 20 * 10; set(St.CAMP); return; }
            miner.start(gatherTarget.getX(), gatherTarget.getY(), gatherTarget.getZ(), false);
            gatherBlocked = 0;
            gatherStart = Long.MAX_VALUE;
        }
        if (ctx.eye().distance(gatherTarget.getLocation().add(0.5, 0.5, 0.5)) <= 4.0 && gatherStart == Long.MAX_VALUE) gatherStart = ctx.tick;
        if (ctx.eye().distance(gatherTarget.getLocation().add(0.5, 0.5, 0.5)) > 4.0) {
            ctx.nav.goTo(gatherTarget.getLocation().add(0.5, 0, 0.5), 2.5, NavOptions.walk());
            ctx.nav.tick();
            if (ctx.nav.failed()) { unreachable.put(gatherTarget.getLocation(), ctx.tick + 20 * 60); gatherTarget = null; }
            return;
        }
        ctx.nav.stop();
        BlockBreaker.Result r = miner.tick();
        // something else is in the way of the crosshair (or it simply takes too long by hand): pick another block
        Block tb = ctx.c.targetedBlock();
        if (r == BlockBreaker.Result.RUNNING && (tb == null || !tb.equals(gatherTarget))) gatherBlocked++;
        if (r == BlockBreaker.Result.RUNNING && (gatherBlocked > 15 || ctx.tick - gatherStart > 20 * 7)) {
            miner.cancel();
            r = BlockBreaker.Result.FAILED;
        }
        if (r == BlockBreaker.Result.RUNNING) return;
        if (r == BlockBreaker.Result.DONE) { gatherDrop = gatherTarget.getLocation().add(0.5, 0, 0.5); gatherDropTick = ctx.tick; }
        else unreachable.put(gatherTarget.getLocation(), ctx.tick + 20 * 60);
        gatherTarget = null;
    }

    /** Nearest exposed block at body height that drops a building block with the tools we have (never the floor we stand on). */
    private Block findGatherBlock(int r) {
        Location me = ctx.pos();
        boolean pick = Inv.has(ctx.player, Inv.PICKAXE);
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -r; dz <= r; dz++) {
            Block b = me.getWorld().getBlockAt(me.getBlockX() + dx, me.getBlockY() + dy, me.getBlockZ() + dz);
            if (!gatherable(b.getType(), pick) || skipUnreachable(b)) continue;
            boolean exposed = false;
            for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                Material n = b.getRelative(f).getType();
                if (n == Material.AIR) { exposed = true; break; }
            }
            if (!exposed) continue;
            // keep what holds the island (and us) up: only blocks with something solid under them
            if (b.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == Material.AIR && dy == 0) continue;
            double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(me);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private static boolean gatherable(Material m, boolean pick) {
        switch (m) {
            case DIRT: case GRASS: case WOOL: case WOOD: case LOG: case LOG_2:
                return true;
            case STONE: case COBBLESTONE: case SANDSTONE: case ENDER_STONE: case STAINED_CLAY: case HARD_CLAY:
            case MOSSY_COBBLESTONE: case SMOOTH_BRICK: case BRICK: case NETHERRACK: case QUARTZ_BLOCK:
                return pick;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ decisions

    private void decide() {
        if (pendingAct != null) {
            if (ctx.tick < pendingUntil) {
                if (pendingLook != null) ctx.motor.aimPoint(Motor.P_TASK, pendingLook, AimIntent.GLANCE, 1.5);
                return;
            }
            Runnable r = pendingAct;
            pendingAct = null;
            r.run();
            return;
        }
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        double armor = Inv.totalArmor(ctx.player);
        double aggr = ctx.persona.skill.aggression;
        int alive = game.alivePlayers().size();
        long sinceOpen = inGameSeconds();
        Location me = ctx.pos();
        Location mid = midGoal(me);
        boolean atMid = mid != null && mid.getWorld() == me.getWorld() && AIMath.hDist(me, mid) < 16;

        double midScore = aggr * 1.2 + (armor >= 8 ? 0.3 : 0) + (blocks >= 20 ? 0.3 : -1.5) + (looted.isEmpty() ? -0.5 : 0);
        double neighScore = 0.55 + aggr * 0.4 + (blocks >= 10 ? 0.2 : -1.5);
        // camping the own island is a short wait for a bridger to come, not the plan: 2020 solo players left fast
        double campScore = (1 - aggr) * 0.7 - (sinceOpen < 25 ? 0.3 : 0) - (alive <= 3 ? 1.0 : 0) - (sinceOpen > 120 ? 0.8 : 0)
                - (atMid ? 1.0 : 0);
        double huntScore = (alive <= 4 ? 1.2 : 0.1) + (sinceOpen > 90 ? 0.6 : 0) + aggr * 0.3;
        midScore += ctx.rng.nextGaussian() * 0.25;
        neighScore += ctx.rng.nextGaussian() * 0.25;
        campScore += ctx.rng.nextGaussian() * 0.25;

        int neigh = nearestIsland();
        if (neigh < 0) neighScore = -9;
        if (mid == null) midScore = -9;
        else if (atMid) {
            // already here: worth staying only for chests nobody emptied yet, else go after people
            buildMidQueue();
            if (lootQueue.isEmpty()) { midScore = -9; huntScore += 1.0; }
            else { set(St.LOOT); nextDecision = ctx.tick + 20 * 20; return; }
        }
        // somebody is in sight: going for them beats wandering off (fights are what SkyWars is)
        Sighting seen = ctx.perception.nearestKnownEnemy(45);
        if (seen != null && seen.visible && !ctx.combat.ignoring(seen.player)) huntScore += 0.4 + aggr * 0.5;

        // out of blocks on an island: nothing above works (every way off is a bridge or a pillar). Dig some up.
        if (blocks < 8 && !atMid && findGatherBlock(6) != null && chance(0.85)) { set(St.GATHER); nextDecision = ctx.tick + 20 * 20; return; }

        double best = Math.max(Math.max(midScore, neighScore), Math.max(campScore, huntScore));
        if (best == midScore) {
            final Location midF = mid;
            commit(new Runnable() { public void run() { travel(midF, true, -1); } }, midF);
            if (game.type().teamSize > 1) ctx.chat.callout(chance(0.5) ? "going mid" : "mid", 0.25);
        } else if (best == neighScore) {
            final Location c = view.islandCenter(neigh);
            final int n = neigh;
            commit(new Runnable() { public void run() { travel(c, false, n); } }, c);
        } else if (best == campScore) {
            campUntil = ctx.tick + AIMath.range(ctx.rng, 20 * 6, 20 * 18);
            set(St.CAMP);
        } else {
            set(St.HUNT);
        }
        nextDecision = ctx.tick + 20 * 20;
    }

    private long inGameSeconds() { return Math.max(0, (ctx.tick - firstOpenTick()) / 20); }

    private long openTick = -1;

    private long firstOpenTick() {
        if (openTick < 0) openTick = ctx.tick;
        return openTick;
    }

    private int nearestIsland() {
        Location me = ctx.pos();
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < view.islandCount(); i++) {
            if (i == island || visitedIslands.contains(i)) continue;
            Location c = view.islandCenter(i);
            if (c == null) continue;
            double d = c.distanceSquared(me);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private final Set<Integer> visitedIslands = new HashSet<>();

    private void travel(Location to, boolean lootMid, int isl) {
        if (to == null) { set(St.HUNT); return; }
        travelTo = to.clone();
        travelLootMid = lootMid;
        travelIsland = isl;
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        NavOptions o = NavOptions.bridging(blocks);
        o.pillar = true;
        o.breakBlocks = true; // SkyWars: everything is breakable; mine out of enclosed islands like players do
        o.maxNodes = 12000;
        travelOpts = o;
        travelRadius = lootMid ? 4 : 5;
        ctx.nav.goTo(travelTo, travelRadius, o);
        set(St.TRAVEL);
    }

    private void travelTick() {
        travelOpts.maxBlocks = Inv.count(ctx.player, Inv.BLOCK);
        ctx.nav.goTo(travelTo, travelRadius, travelOpts);
        ctx.nav.tick();
        if (ctx.nav.arrived()) {
            if (travelLootMid) { buildMidQueue(); set(St.LOOT); }
            else if (travelIsland >= 0) { visitedIslands.add(travelIsland); buildLootQueue(travelIsland); set(St.LOOT); }
            else set(St.DECIDE);
            return;
        }
        if (ctx.nav.failed() || inState() > 20 * 120) {
            ctx.nav.stop();
            if (travelIsland >= 0) visitedIslands.add(travelIsland);
            boolean noBlocks = Inv.count(ctx.player, Inv.BLOCK) < 4;
            set(noBlocks ? (findGatherBlock(6) != null ? St.GATHER : St.CAMP) : St.DECIDE);
            campUntil = ctx.tick + 20 * 20;
        }
    }

    private void campTick() {
        // hold position near the island edge facing out, throw at bridgers
        if (ctx.tick > campUntil) { set(St.DECIDE); return; }
        Sighting s = ctx.perception.nearestKnownEnemy(30);
        if (s != null && s.visible && !ctx.combat.ignoring(s.player)) {
            ctx.motor.aimEntity(Motor.P_TASK, s.player, AimIntent.COMBAT);
            if (s.estimate(ctx.tick).distance(ctx.pos()) < 22 && (Inv.has(ctx.player, Inv.THROWABLE) || Inv.has(ctx.player, Inv.BOW))) {
                ctx.combat.engage(s.player);
            }
        }
    }

    private void huntTick() {
        Sighting s = ctx.perception.nearestKnownEnemy(150);
        if (s != null && ctx.combat.ignoring(s.player)) s = null;
        Location to;
        if (s != null) to = s.estimate(ctx.tick);
        else {
            // nobody in memory: SkyWars maps are open, you can see where the others are. Go for the nearest one
            // we have not just given up on; the mid if nobody is left to pick.
            to = null;
            Location me = ctx.pos();
            double bd = Double.MAX_VALUE;
            for (Player p : game.alivePlayers()) {
                if (p == ctx.player || isAlly(p) || p.getWorld() != me.getWorld() || ctx.combat.ignoring(p)) continue;
                double d = p.getLocation().distanceSquared(me);
                if (d < bd) { bd = d; to = p.getLocation(); }
            }
            if (to == null) to = view.mid();
        }
        if (to == null) { if (inState() > 100) set(St.DECIDE); return; }
        NavOptions o = NavOptions.bridging(Inv.count(ctx.player, Inv.BLOCK));
        o.pillar = true;
        o.breakBlocks = true; // SkyWars: everything is breakable; mine out of enclosed islands like players do
        o.maxNodes = 12000;
        o.avoidEnemies = false; // we are going for them
        ctx.nav.follow(to, 3, o);
        ctx.nav.tick();
        if (ctx.nav.arrived() || ctx.nav.failed()) {
            boolean stuck = ctx.nav.failed();
            ctx.nav.stop();
            if (stuck && Inv.count(ctx.player, Inv.BLOCK) < 8 && findGatherBlock(6) != null) { set(St.GATHER); return; }
            if (inState() > 100) set(St.DECIDE);
        }
        if (ctx.tick > nextDecision) { nextDecision = ctx.tick + 400; set(St.DECIDE); }
    }

    private void endTick() {
        ctx.nav.stop();
        if (won() && --jumpTimer <= 0) { ctx.motor.jump(true); jumpTimer = AIMath.range(ctx.rng, 2, 20); }
    }

    @Override
    public void onDeath(Player killer, boolean voidDeath) {
        super.onDeath(killer, voidDeath);
        if (looter != null) looter.cancel();
    }
}
