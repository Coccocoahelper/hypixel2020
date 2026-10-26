package net.h2020.bots.ai.perception;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Raycast;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human-like awareness. Every few ticks (staggered per bot) the bot "looks": players inside the ~110 degree vision
 * cone with clear line of sight fill a detection meter at a rate depending on angle, distance, attention and how
 * conspicuous they are (sneaking players are hard to notice, sprinting/fighting ones easy). After the meter fills and
 * a reaction delay passes, the player becomes known and is remembered with last position/velocity, decaying over
 * time. Hearing (footsteps, block placing, bow shots, hits) raises the meter and queues a glance toward the noise.
 * Getting hit makes the attacker known instantly and queues a flick after the bot's reaction time.
 * Nobody is omniscient: someone sneaking up from behind can stay undetected.
 */
public final class Perception {
    public interface Filter {
        /** Should this player be tracked at all (alive participant in our game/world). */
        boolean relevant(Player p);
        boolean ally(Player p);
    }

    public static final Filter WORLD_ALL = new Filter() {
        public boolean relevant(Player p) { return p.getGameMode() != GameMode.SPECTATOR; }
        public boolean ally(Player p) { return false; }
    };

    private final BotContext ctx;
    private final Map<UUID, Sighting> mem = new HashMap<>();
    private Filter filter = WORLD_ALL;
    private final int phase;
    private long lastScan = -100, lastSoundTick;

    // queued look requests
    private Location glanceAt;
    private long glanceDue = -1;
    private Player flickAt;
    private long flickDue = -1;

    public Perception(BotContext ctx) {
        this.ctx = ctx;
        this.phase = (int) (Math.abs(ctx.persona.seed) % 4);
    }

    public void setFilter(Filter f) { filter = f == null ? WORLD_ALL : f; }
    public Filter filter() { return filter; }

    public Collection<Sighting> all() { return mem.values(); }

    public Sighting get(Player p) { return p == null ? null : mem.get(p.getUniqueId()); }

    public void forgetAll() { mem.clear(); glanceAt = null; flickAt = null; }

    // ------------------------------------------------------------------------------------------------- scanning

    public void tick() {
        long now = ctx.tick;
        if ((now + phase) % 4 != 0) return;
        lastScan = now;
        Player self = ctx.player;
        Location eye = ctx.eye();
        Vector look = AIMath.lookVector(ctx.c.yaw(), ctx.c.pitch());
        double attention = 0.45 + 0.55 * ctx.persona.skill.awareness;
        List<Player> world = self.getWorld().getPlayers();
        for (Player p : world) {
            if (p == self || !p.isOnline() || p.isDead()) continue;
            if (!filter.relevant(p)) { mem.remove(p.getUniqueId()); continue; }
            Sighting s = mem.get(p.getUniqueId());
            if (s == null) { s = new Sighting(p); mem.put(p.getUniqueId(), s); }
            s.ally = filter.ally(p);
            scan(s, p, eye, look, attention, now);
        }
        // hearing
        for (SoundBus.Sound snd : SoundBus.heard(eye, lastSoundTick, self)) {
            if (snd.source == null || !filter.relevant(snd.source)) continue;
            Sighting s = mem.get(snd.source.getUniqueId());
            if (s == null) continue;
            onNoise(s, new Location(snd.world, snd.x, snd.y, snd.z), snd.kind == SoundBus.Kind.BOW || snd.kind == SoundBus.Kind.HIT ? 0.5 : 0.3);
        }
        lastSoundTick = SoundBus.now();
        // drop dead/forgotten entries
        for (Iterator<Sighting> it = mem.values().iterator(); it.hasNext(); ) {
            Sighting s = it.next();
            if (!s.player.isOnline() || s.player.getWorld() != self.getWorld()) { it.remove(); continue; }
            if (s.known && s.confidence(now) < 0.04 && !s.visible) { s.known = false; s.meter = 0; }
            s.threat = threat(s, now);
        }
    }

    private void scan(Sighting s, Player p, Location eye, Vector look, double attention, long now) {
        Location tl = p.getLocation();
        Location torso = tl.clone().add(0, p.isSneaking() ? 1.0 : 1.2, 0);
        double dx = torso.getX() - eye.getX(), dy = torso.getY() - eye.getY(), dz = torso.getZ() - eye.getZ();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean invisible = p.hasPotionEffect(PotionEffectType.INVISIBILITY);
        boolean wasVisible = s.visible;
        if (s.scanPos != null && s.scanPos.getWorld() == tl.getWorld() && now > s.scanTick) {
            double mx = tl.getX() - s.scanPos.getX(), mz = tl.getZ() - s.scanPos.getZ();
            s.scanSpeed = Math.sqrt(mx * mx + mz * mz) / (now - s.scanTick);
        }
        s.scanPos = tl.clone();
        s.scanTick = now;
        s.visible = false;
        if (d < 72 && !invisible) {
            double cos = (dx * look.getX() + dy * look.getY() + dz * look.getZ()) / Math.max(1e-6, d);
            double ang = Math.toDegrees(Math.acos(AIMath.clamp(cos, -1, 1)));
            if (ang < 80 || d < 2.0) {
                int los = d < 2.0 ? Raycast.CLEAR : Raycast.lineOfSight(eye.getWorld(), eye, torso);
                if (los == Raycast.UNKNOWN) los = wasVisible ? Raycast.CLEAR : Raycast.BLOCKED;
                if (los == Raycast.CLEAR) {
                    double angF = ang < 25 ? 1.0 : ang < 55 ? 0.65 : 0.22;
                    double distF = AIMath.clamp(1.25 - d / 45.0, 0.08, 1.25);
                    double cons = p.isSneaking() ? 0.45 : (p.isSprinting() ? 1.3 : 1.0);
                    if (s.known) cons *= 1.6; // easy to keep track of someone you already know about
                    s.meter = Math.min(1.5, s.meter + 0.55 * angF * distF * cons * attention);
                    s.visible = s.known || s.meter >= 1.0;
                }
            }
        }
        // footsteps when not seen
        if (!s.visible && !p.isSneaking()) {
            double sp = s.scanSpeed;
            double radius = p.isSprinting() && sp > 0.15 ? 13 : (sp > 0.05 ? 7.5 : 0);
            if (radius > 0 && d < radius && ctx.rng.nextDouble() < 0.35) onNoise(s, tl, 0.18);
        }
        if (!s.visible) s.meter = Math.max(0, s.meter - 0.04);
        if (s.meter >= 1.0 && !s.known) {
            if (s.noticeAt < 0) s.noticeAt = now + Math.max(1, AIMath.msToTicks(ctx.rng, ctx.persona.skill.reactionMs));
            if (now >= s.noticeAt) { s.known = true; s.noticeAt = -1; }
        }
        if (s.visible && s.known) {
            if (s.lastPos != null && now - s.lastSeenTick <= 8) {
                double t = Math.max(1, now - s.lastSeenTick);
                s.lastVel = tl.toVector().subtract(s.lastPos.toVector()).multiply(1.0 / t);
            } else s.lastVel = new Vector();
            s.lastPos = tl.clone();
            s.lastSeenTick = now;
        }
    }

    private void onNoise(Sighting s, Location at, double amount) {
        long now = ctx.tick;
        s.meter = Math.min(1.2, s.meter + amount * (0.5 + ctx.persona.skill.awareness));
        s.lastHeardTick = now;
        if (!s.visible) {
            // heard but unseen: imprecise position memory
            s.lastPos = at.clone().add((ctx.rng.nextDouble() - 0.5) * 3, 0, (ctx.rng.nextDouble() - 0.5) * 3);
            if (s.lastSeenTick < now - 40) s.lastSeenTick = now - 40;
            if (glanceDue < 0 && ctx.rng.nextDouble() < 0.35 + 0.6 * ctx.persona.skill.awareness) {
                glanceAt = at.clone().add(0, 1.2, 0);
                glanceDue = now + AIMath.msToTicks(ctx.rng, ctx.persona.skill.reactionMs * 1.3);
            }
        }
    }

    /** We got hit by `attacker`. */
    public void onHurt(Player attacker) {
        if (attacker == null) return;
        Sighting s = mem.get(attacker.getUniqueId());
        if (s == null) { s = new Sighting(attacker); mem.put(attacker.getUniqueId(), s); }
        long now = ctx.tick;
        s.known = true;
        s.meter = 1.2;
        s.lastHurtMeTick = now;
        s.hitsOnMe++;
        s.lastPos = attacker.getLocation().clone();
        if (s.lastSeenTick < now - 10) s.lastSeenTick = now - 10;
        // flick if they are not roughly in front of us
        Location eye = ctx.eye();
        float want = AIMath.yawTo(eye, attacker.getLocation());
        if (Math.abs(AIMath.angleDiff(want, ctx.c.yaw())) > 35 && flickDue < 0) {
            flickAt = attacker;
            flickDue = now + Math.max(2, AIMath.msToTicks(ctx.rng, ctx.persona.skill.reactionMs));
        }
    }

    public void onHitLanded(Player victim) {
        Sighting s = get(victim);
        if (s != null) s.lastHitByMeTick = ctx.tick;
    }

    /** A due glance target (consumed). */
    public Location takeGlance() {
        if (glanceDue >= 0 && ctx.tick >= glanceDue) {
            Location g = glanceAt;
            glanceAt = null; glanceDue = -1;
            return g;
        }
        return null;
    }

    /** True when a noise glance is due (not consumed). */
    public boolean glanceDue() { return glanceDue >= 0 && ctx.tick >= glanceDue; }

    /** A due flick target (consumed). */
    public Player takeFlick() {
        if (flickDue >= 0 && ctx.tick >= flickDue) {
            Player p = flickAt;
            flickAt = null; flickDue = -1;
            return p != null && p.isOnline() && !p.isDead() ? p : null;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- threat

    private double threat(Sighting s, long now) {
        if (!s.known || s.ally || s.lastPos == null || s.lastPos.getWorld() != ctx.player.getWorld()) return 0;
        Player p = s.player;
        Location me = ctx.pos();
        Location est = s.estimate(now);
        double d = est.distance(me);
        double t = 4.0 / (1.0 + d / 6.0);
        t += Inv.totalArmor(p) / 7.0;
        t += Items.meleeValue(p.getItemInHand()) / 4.0;
        // approaching
        Vector toMe = me.toVector().subtract(est.toVector());
        double len = toMe.length();
        if (len > 0.1) {
            double closing = s.lastVel.dot(toMe.multiply(1.0 / len));
            if (closing > 0.08) t += 1.5;
            // bridging toward me: approaching while over the void
            if (closing > 0.05 && ctx.world().voidBelow(floor(est.getX()), floor(est.getY()), floor(est.getZ()) , 10) == false
                    && ctx.world().voidSides(floor(est.getX()), floor(est.getY()), floor(est.getZ())) >= 2) t += 2.0;
        }
        double sinceHurt = (now - s.lastHurtMeTick) / 20.0;
        if (sinceHurt < 8) t += 3.0 * (1 - sinceHurt / 8);
        if (p.getItemInHand() != null && p.getItemInHand().getType().name().equals("BOW") && d > 8) t += 1.0;
        // opportunity: low health, not looking at us
        double opp = 0;
        if (p.getHealth() < 8) opp += 2;
        float yawToMe = AIMath.yawTo(est, me);
        if (Math.abs(AIMath.angleDiff(p.getLocation().getYaw(), yawToMe)) > 100) opp += 1;
        s.opportunity = opp;
        return t * s.confidence(now);
    }

    /** Most threatening known enemy (null if none). */
    public Sighting bestThreat() {
        Sighting best = null;
        for (Sighting s : mem.values()) {
            if (!s.known || s.ally || s.threat <= 0) continue;
            if (best == null || s.threat + s.opportunity > best.threat + best.opportunity) best = s;
        }
        return best;
    }

    public Sighting nearestKnownEnemy(double maxDist) {
        Sighting best = null;
        double bd = maxDist;
        Location me = ctx.pos();
        for (Sighting s : mem.values()) {
            if (!s.known || s.ally || s.lastPos == null || s.lastPos.getWorld() != me.getWorld()) continue;
            double d = s.estimate(ctx.tick).distance(me);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    public List<Sighting> visibleEnemies() {
        List<Sighting> out = new ArrayList<>();
        for (Sighting s : mem.values()) if (s.visible && s.known && !s.ally) out.add(s);
        return out;
    }

    public int enemiesWithin(double r) {
        int n = 0;
        Location me = ctx.pos();
        for (Sighting s : mem.values()) {
            if (!s.known || s.ally || s.lastPos == null || s.lastPos.getWorld() != me.getWorld()) continue;
            if (s.estimate(ctx.tick).distance(me) < r) n++;
        }
        return n;
    }

    public long lastScanTick() { return lastScan; }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
