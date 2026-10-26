package net.h2020.bots.ai.brain;

import net.h2020.api.game.BedwarsView;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.brain.bedwars.BedDefense;
import net.h2020.bots.ai.brain.bedwars.BwTask;
import net.h2020.bots.ai.brain.bedwars.CollectTask;
import net.h2020.bots.ai.brain.bedwars.DefendTask;
import net.h2020.bots.ai.brain.bedwars.GuardTask;
import net.h2020.bots.ai.brain.bedwars.Role;
import net.h2020.bots.ai.brain.bedwars.RushTask;
import net.h2020.bots.ai.brain.bedwars.ShareTask;
import net.h2020.bots.ai.brain.bedwars.ShopPlanner;
import net.h2020.bots.ai.brain.bedwars.ShopTask;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Bed Wars decision layer: a utility-scored task selector over shop trips, iron collection, bed defense, rushing,
 * diamond/emerald runs, team upgrades, guarding and resource sharing, with fight and "enemy at our bed" interrupts.
 * Roles (defender / rusher / farmer) come from personality and, in team modes, from coordination among the
 * team's bots (one defends, one rushes). Reacts to BED DESTRUCTION (final-life caution), respawns (re-buys gear).
 */
public final class BedwarsBrain extends GameBrain {
    private final BedwarsView view;
    private TeamColor color;
    private Role role = Role.FARMER;
    private BwTask task;
    private BedDefense defense;
    private IdleBehavior pregame;
    private long playStart = -1;
    private long nextDecision;
    private boolean bedWasAlive = true;
    private boolean finalLife;
    private boolean justRespawned;
    private long lastShare = -100000;
    private long lastRoleCheck = -1000;
    private int defenses;
    private int jumpTimer;
    private long bedAlertCooldown;
    /** This farmer goes for the emeralds at mid rather than the diamond islands. */
    private boolean emeraldRunner, emeraldPicked;
    private double defendDoneIntegrity = 2;
    private long defendRetryAt;
    private final java.util.Map<Class<?>, Long> quickFailUntil = new java.util.HashMap<>();

    public BedwarsBrain(BedwarsView view) {
        super(view);
        this.view = view;
    }

    // ---- accessors for tasks ----
    public BedwarsView view() { return view; }
    public TeamColor color() { return color; }
    public Role role() { return role; }

    @Override
    public String toString() {
        return "BedwarsBrain[" + role + " " + (task == null ? "-" : task.name()) + (finalLife ? " final" : "") + "]";
    }
    public boolean finalLife() { return finalLife; }
    public boolean teamMode() { return game.type().teamSize > 1; }
    public int blocks() { return Inv.count(ctx.player, Inv.BLOCK); }
    public int gameSeconds() { return playStart < 0 ? 0 : (int) ((ctx.tick - playStart) / 20); }

    public BedDefense defense() {
        if (defense == null && color != null && view.bedLocation(color) != null) defense = BedDefense.of(view.bedLocation(color));
        return defense;
    }

    @Override
    protected void onGameStart() {
        pregame = new IdleBehavior(ctx, 7, false);
        GameTeam t = myTeam();
        if (t != null) color = t.color();
        configureCombat();
    }

    private void configureCombat() {
        ctx.combat.allowThrowables = false;
        ctx.combat.allowBow = true;
        ctx.combat.allowGapple = true;
        ctx.combat.allowPearl = ctx.persona.tryhard;
        ctx.combat.allowFireball = ctx.persona.tryhard || gameSeconds() > 240;
        ctx.combat.leash = role == Role.DEFENDER ? 22 : 35;
        NavOptions chase = NavOptions.bridging(8);
        chase.maxNodes = 3500;
        ctx.combat.chaseOptions = net.h2020.bots.ai.combat.Combat.chase(chase);
    }

    @Override
    protected boolean canAct() {
        return super.canAct() && !view.isRespawning(ctx.player);
    }

    @Override
    protected void onInactive() {
        if (task != null) { task.abort(); task = null; }
    }

    @Override
    protected void onActive() {
        if (playStart >= 0) justRespawned = true;
        nextDecision = ctx.tick + AIMath.range(ctx.rng, 4, 16);
    }

    @Override
    protected void think() {
        pollState();
        if (color == null) { GameTeam t = myTeam(); if (t != null) color = t.color(); }
        GameState gs = game.state();
        if (gs == GameState.WAITING || gs == GameState.STARTING) {
            if (pregame != null) pregame.tick();
            return;
        }
        if (gs == GameState.ENDING) {
            if (task != null) { task.abort(); task = null; }
            ctx.nav.stop();
            if (won() && --jumpTimer <= 0) { ctx.motor.jump(true); jumpTimer = AIMath.range(ctx.rng, 2, 25); }
            return;
        }
        if (playStart < 0) {
            playStart = ctx.tick;
            assignRole();
            nextDecision = ctx.tick + AIMath.range(ctx.rng, 2, 12);
        }
        if (ctx.tick - lastRoleCheck > 600) assignRole();
        if (ctx.tick % 20 == 0) checkBed();

        // fights
        boolean locked = task != null && !task.interruptible();
        if (!locked && combatPolicy(engageRange())) {
            if (task != null && !(task instanceof RushTask) && !(task instanceof GuardTask)) { task.abort(); task = null; }
            ctx.combat.tick();
            return;
        }
        // someone at our bed
        if (color != null && view.bedAlive(color) && ctx.tick > bedAlertCooldown && !(task instanceof GuardTask) && enemyNearBed()) {
            double d = ctx.pos().distance(view.bedLocation(color));
            if (d < 45 && (role == Role.DEFENDER || d < 22)) {
                switchTo(new GuardTask(this, 20 * 25));
                if (teamMode()) ctx.chat.callout(ctx.rng.nextBoolean() ? "someone at bed" : "bed", 0.4);
                bedAlertCooldown = ctx.tick + 400;
            }
        }
        if (task != null) {
            boolean running;
            try { running = task.tick(); } catch (RuntimeException ex) { running = false; }
            if (!running) {
                if (task instanceof DefendTask && ((DefendTask) task).placed() > 3) {
                    defenses++;
                    if (teamMode() && defenses == 1) ctx.chat.callout("bed defended", 0.5);
                }
                // a trip that gave up straight away (no route yet, nav cooldown): don't pick it again every tick
                if ((task instanceof RushTask || task instanceof CollectTask) && ctx.tick - task.startedTick() < 40) {
                    quickFailUntil.put(task.getClass(), ctx.tick + 20 * 8);
                }
                if (task instanceof DefendTask) {
                    // whatever is still open could not be placed from anywhere: don't stand there retrying it
                    BedDefense d = defense();
                    defendDoneIntegrity = d == null ? 1 : d.integrity(ctx.world());
                    defendRetryAt = ctx.tick + 20 * 30;
                }
                task = null;
                nextDecision = ctx.tick + AIMath.range(ctx.rng, 2, 10);
            }
            return;
        }
        if (ctx.tick < nextDecision) return;
        BwTask next = decide();
        if (next != null) switchTo(next);
        nextDecision = ctx.tick + 10;
    }

    private void switchTo(BwTask t) {
        if (task != null) task.abort();
        task = t;
    }

    private double engageRange() {
        double r;
        switch (role) {
            case RUSHER: r = 12; break;
            case DEFENDER: r = 10; break;
            default: r = 9;
        }
        if (finalLife) r *= 0.75;
        return r;
    }

    // ------------------------------------------------------------------------------------------------ roles

    private void assignRole() {
        lastRoleCheck = ctx.tick;
        double a = ctx.persona.skill.aggression;
        if (!teamMode()) {
            role = a > 0.62 ? Role.RUSHER : (a < 0.3 ? Role.DEFENDER : Role.FARMER);
            if (!emeraldPicked) { emeraldPicked = true; emeraldRunner = ctx.rng.nextDouble() < 0.3; }
            configureCombat();
            return;
        }
        // coordinate with the other bots on the team: most aggressive rushes, least aggressive defends
        List<Player> mates = new ArrayList<>(teammates());
        mates.add(ctx.player);
        List<Player> bots = new ArrayList<>();
        for (Player p : mates) {
            BaseBrain b = BotAI.brainOf(p);
            if (b instanceof BedwarsBrain && b.context() != null) bots.add(p);
        }
        Collections.sort(bots, new Comparator<Player>() {
            public int compare(Player x, Player y) {
                double ax = BotAI.brainOf(x).context().persona.skill.aggression;
                double ay = BotAI.brainOf(y).context().persona.skill.aggression;
                int c = Double.compare(ay, ax);
                return c != 0 ? c : x.getName().compareTo(y.getName());
            }
        });
        int idx = bots.indexOf(ctx.player);
        int n = bots.size();
        boolean humansOnTeam = mates.size() > n;
        if (n <= 1) role = humansOnTeam ? (a > 0.5 ? Role.RUSHER : Role.DEFENDER) : (a > 0.62 ? Role.RUSHER : Role.FARMER);
        else if (idx == 0) role = Role.RUSHER;
        else if (idx == n - 1) role = Role.DEFENDER;
        else role = Role.FARMER;
        // with two farmers one runs diamonds, the other the emeralds at mid
        emeraldRunner = role == Role.FARMER && n >= 4 && idx == n - 2;
        configureCombat();
    }

    // ------------------------------------------------------------------------------------------------ bed

    private void checkBed() {
        if (color == null) return;
        boolean alive = view.bedAlive(color);
        if (bedWasAlive && !alive) {
            finalLife = true;
            if (task instanceof DefendTask || task instanceof GuardTask) { task.abort(); task = null; }
            if (teamMode()) ctx.chat.callout(pick("bed gone", "they broke bed", "bed", "bro our bed"), 0.55);
            else ctx.chat.callout(pick("bruh", "bro", "omg", "really"), 0.3);
            configureCombat();
        }
        bedWasAlive = alive;
    }

    private String pick(String... s) { return s[ctx.rng.nextInt(s.length)]; }

    private boolean enemyNearBed() {
        Location bed = view.bedLocation(color);
        if (bed == null) return false;
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.lastPos == null) continue;
            if (s.confidence(ctx.tick) < 0.3) continue;
            if (s.estimate(ctx.tick).distance(bed) < 9) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ utility

    private BwTask decide() {
        if (color == null) return null;
        int gsec = gameSeconds();
        int iron = ShopPlanner.iron(ctx.player);
        int dia = ShopPlanner.diamonds(ctx.player);
        int blocks = blocks();
        Location spawn = view.teamSpawn(color);
        Location gen = view.teamGenerator(color);
        double toBase = spawn == null ? 0 : ctx.pos().distance(spawn);
        boolean nearBase = toBase < 30;
        boolean bedAlive = view.bedAlive(color);
        BedDefense def = defense();
        double integrity = def == null ? 1 : def.integrity(ctx.world());
        boolean canShop = !ShopPlanner.plan(ctx.player, role, ctx.persona.tryhard, gsec, finalLife).isEmpty();

        double sShop = canShop ? (blocks < 16 ? 1.2 : 0.75) * (nearBase ? 1 : 0.35) : 0;
        if (justRespawned && canShop) sShop += 0.7;
        double sUpg = (dia >= 2 && nearBase && (role == Role.FARMER || dia >= 4 || gsec > 300)) ? 0.85 : 0;
        double sIron = 0;
        // opening: grab just enough for the first wool (2020 players were at the shop within ~10 s), not a pile
        int openingIron = role == Role.RUSHER ? 12 : 8;
        boolean opening = gsec < 40 && blocks < 16 && iron < openingIron;
        if (opening) sIron = 1.35;
        else if (!canShop && nearBase && (blocks < 16 || role == Role.DEFENDER)) sIron = 0.5;
        double sDefend = 0;
        // after a defend pass, only come back for it when the cover got worse (someone broke in) or a while later
        boolean defendWorthIt = integrity < defendDoneIntegrity - 0.01 || ctx.tick >= defendRetryAt;
        if (bedAlive && blocks >= 8 && integrity < 0.99 && defendWorthIt) {
            if (role == Role.DEFENDER) sDefend = 1.25;
            else if (role == Role.FARMER) sDefend = defenses == 0 && !teamHasDefender() ? 0.95 : 0.3;
            else sDefend = defenses == 0 && !teamMode() && ctx.rng.nextDouble() < 0.3 ? 0.9 : 0.2;
            if (!nearBase) sDefend *= 0.4;
        }
        boolean secondLayer = role == Role.DEFENDER && (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE)) || blocks >= 40);
        if (bedAlive && def != null && secondLayer && defendWorthIt && integrity >= 0.99 && !def.open(def.layer2, ctx.world()).isEmpty() && blocks >= 12) sDefend = Math.max(sDefend, 0.9);

        TeamColor target = chooseTarget();
        double sRush = 0;
        if (target != null && blocks >= 16) {
            switch (role) {
                case RUSHER: sRush = 1.0 + (ShopPlanner.swordTier(ctx.player) >= 2 ? 0.25 : 0); break;
                case FARMER: sRush = gsec > 240 ? 0.8 : 0.1; break;
                default: sRush = gsec > 600 || !bedAlive ? 0.6 : 0.05;
            }
            if (finalLife) sRush *= 0.6;
            if (ShopPlanner.armorTier(ctx.player) <= 1 && gsec > 300) sRush *= 0.7;
        }
        double sDia = 0;
        List<Location> dgens = view.diamondGenerators();
        if (!dgens.isEmpty() && blocks >= 12 && dia < 8) {
            // farmers head out as soon as they have blocks (and the bed has its first layer, see sDefend)
            if (role == Role.FARMER && gsec > 15) sDia = 0.9;
            else if (role == Role.DEFENDER && !teamMode() && gsec > 60 && integrity >= 0.99) sDia = 0.6;
            else if (ctx.persona.tryhard && gsec > 90) sDia = 0.5;
            else if (gsec > 400) sDia = 0.3;
        }
        double sEm = 0;
        if (!view.emeraldGenerators().isEmpty() && blocks >= 24 && ShopPlanner.emeralds(ctx.player) < 6) {
            if (role == Role.FARMER && emeraldRunner && gsec > 90) sEm = 0.95;
            else if ((ctx.persona.tryhard || role == Role.FARMER) && gsec > 300) sEm = 0.6;
        }
        double sGuard = bedAlive ? (role == Role.DEFENDER ? 0.55 : 0.12) : 0;
        double sShare = 0;
        Player needy = null;
        if (teamMode() && iron >= 32 && ctx.tick - lastShare > 1200) {
            needy = needyMate();
            if (needy != null) sShare = 0.7;
        }

        if (cooling(RushTask.class)) sRush = 0;
        if (cooling(CollectTask.class)) { sDia = 0; sEm = 0; if (!opening) sIron = 0; }
        double[] sc = {sShop, sUpg, sIron, sDefend, sRush, sDia, sEm, sGuard, sShare};
        for (int i = 0; i < sc.length; i++) if (sc[i] > 0) sc[i] += ctx.rng.nextGaussian() * 0.08;
        int best = 0;
        for (int i = 1; i < sc.length; i++) if (sc[i] > sc[best]) best = i;
        if (sc[best] <= 0.02) return new GuardTask(this, 20 * 10);
        justRespawned = false;
        switch (best) {
            case 0: return new ShopTask(this, false);
            case 1: return new ShopTask(this, true);
            case 2:
                if (teamMode() && blocks < 16) ctx.chat.callout("need iron", 0.06);
                return new CollectTask(this, gen, CollectTask.Kind.IRON, opening ? openingIron : Math.max(iron + 8, 24), 20 * 25);
            case 3: return new DefendTask(this, secondLayer);
            case 4: return new RushTask(this, target);
            case 5: return new CollectTask(this, nearest(dgens), CollectTask.Kind.DIAMOND, dia + AIMath.range(ctx.rng, 2, 4), 20 * 45);
            case 6: return new CollectTask(this, nearest(view.emeraldGenerators()), CollectTask.Kind.EMERALD,
                    ShopPlanner.emeralds(ctx.player) + 2, 20 * 50);
            case 7: return new GuardTask(this, 20 * AIMath.range(ctx.rng, 8, 25));
            case 8: lastShare = ctx.tick; ctx.chat.callout("here", 0.2); return new ShareTask(this, needy, Material.IRON_INGOT, AIMath.range(ctx.rng, 4, 10));
            default: return null;
        }
    }

    private boolean cooling(Class<?> c) {
        Long until = quickFailUntil.get(c);
        return until != null && ctx.tick < until;
    }

    private boolean teamHasDefender() {
        for (Player p : teammates()) {
            BaseBrain b = BotAI.brainOf(p);
            if (b instanceof BedwarsBrain && ((BedwarsBrain) b).role == Role.DEFENDER) return true;
        }
        return false;
    }

    private Player needyMate() {
        for (Player p : teammates()) {
            if (p.isDead() || view.isRespawning(p) || p.getLocation().distance(ctx.pos()) > 15) continue;
            if (Inv.count(p, Material.IRON_INGOT) < 8) return p;
        }
        return null;
    }

    private Location nearest(List<Location> ls) {
        Location me = ctx.pos();
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location l : ls) {
            if (l.getWorld() != me.getWorld()) continue;
            double d = l.distanceSquared(me);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    /** Nearest enemy team with a bed (or players when all beds are gone). Team bots rush the same target. */
    private TeamColor chooseTarget() {
        Location from = view.teamSpawn(color);
        if (from == null) from = ctx.pos();
        TeamColor best = null;
        double bd = Double.MAX_VALUE;
        for (GameTeam t : game.teams()) {
            if (t.color() == color || !t.alive()) continue;
            boolean bed = view.bedAlive(t.color());
            Location b = view.bedLocation(t.color());
            if (b == null) continue;
            double d = b.distance(from) + (bed ? 0 : 60) + (t.members().isEmpty() ? 500 : 0);
            if (d < bd) { bd = d; best = t.color(); }
        }
        return best;
    }

    @Override
    public void onDeath(Player killer, boolean voidDeath) {
        super.onDeath(killer, voidDeath);
        if (task != null) { task.abort(); task = null; }
    }

    @Override
    protected void onStop() {
        if (task != null) task.abort();
    }
}
