package net.h2020.bedwars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Utility items: auto-igniting TNT, fireballs, bridge eggs, bedbugs, dream defenders and sponges. */
final class Gadgets {
    static final int TNT_FUSE = 52;
    private static final long FIREBALL_COOLDOWN_MS = 500;

    /** A team-owned mob (bedbug / dream defender). */
    static final class OwnedMob {
        final LivingEntity entity;
        final Player owner;
        final BwTeam team;
        final String label;
        final long expiresAt;
        final int lifetimeTicks;

        OwnedMob(LivingEntity entity, Player owner, BwTeam team, String label, long expiresAt, int lifetimeTicks) {
            this.entity = entity; this.owner = owner; this.team = team; this.label = label; this.expiresAt = expiresAt;
            this.lifetimeTicks = lifetimeTicks;
        }
    }

    private static final class BridgeEgg {
        final Egg egg;
        final BwTeam team;
        final Location start;
        int ticks;
        BridgeEgg(Egg egg, BwTeam team, Location start) { this.egg = egg; this.team = team; this.start = start; }
    }

    private final BedwarsGame g;
    private final Random random = new Random();
    private final Map<UUID, Player> tntOwners = new HashMap<>();
    private final Map<UUID, TNTPrimed> tntEntities = new HashMap<>();
    private final Map<UUID, OwnedMob> mobs = new HashMap<>();
    private final List<BridgeEgg> eggs = new ArrayList<>();

    Gadgets(BedwarsGame g) { this.g = g; }

    // ------------------------------------------------------------------ TNT

    /** Placed TNT ignites instantly (next tick, after the place event finished). */
    void placeTnt(final Block b, final Player owner) {
        Bukkit.getScheduler().runTask(g.module.plugin(), new Runnable() {
            @Override public void run() {
                if (b.getType() != Material.TNT) return;
                b.setType(Material.AIR);
                g.unmarkPlaced(b);
                TNTPrimed t = b.getWorld().spawn(b.getLocation().add(0.5, 0, 0.5), TNTPrimed.class);
                t.setFuseTicks(TNT_FUSE);
                t.setIsIncendiary(false);
                tntOwners.put(t.getUniqueId(), owner);
                tntEntities.put(t.getUniqueId(), t);
                b.getWorld().playSound(b.getLocation(), Sound.FUSE, 1f, 1f);
            }
        });
    }

    Player tntOwner(Entity tnt) { return tntOwners.get(tnt.getUniqueId()); }

    // ------------------------------------------------------------------ fireball

    boolean launchFireball(Player p) {
        BwPlayer bp = g.data(p);
        if (bp == null) return false;
        long now = System.currentTimeMillis();
        if (now - bp.lastFireball < FIREBALL_COOLDOWN_MS) return false;
        bp.lastFireball = now;
        Fireball f = p.launchProjectile(Fireball.class);
        f.setIsIncendiary(false);
        f.setYield(2.0f);
        Vector dir = p.getEyeLocation().getDirection().normalize();
        f.setDirection(dir);
        f.setVelocity(dir.multiply(0.8));
        ItemStack hand = p.getItemInHand();
        if (hand != null) {
            if (hand.getAmount() <= 1) p.setItemInHand(null);
            else { hand.setAmount(hand.getAmount() - 1); p.setItemInHand(hand); }
        }
        return true;
    }

    /**
     * Hypixel-style explosion knockback (fireball/TNT jumping). Applied a tick later so it replaces the vanilla
     * explosion motion the client just received.
     */
    void explosionKnockback(final Location center, final boolean fireball) {
        final double radius = fireball ? 3.5 : 4.5;
        Bukkit.getScheduler().runTask(g.module.plugin(), new Runnable() {
            @Override public void run() {
                for (BwPlayer bp : g.playingPlayers()) {
                    Player p = bp.player;
                    if (p.getWorld() != center.getWorld()) continue;
                    Location l = p.getLocation().add(0, 0.6, 0);
                    double d = l.distance(center);
                    if (d > radius) continue;
                    double f = 1.0 - d / radius;
                    Vector dir = l.toVector().subtract(center.toVector());
                    if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 1, 0);
                    dir.normalize();
                    double h = (fireball ? 1.15 : 1.35) * f;
                    double v = (fireball ? 0.55 : 0.65) + 0.45 * f * Math.max(0.2, dir.getY());
                    Vector vel = new Vector(dir.getX() * h, Math.min(1.1, v), dir.getZ() * h);
                    p.setVelocity(p.getVelocity().multiply(0.2).add(vel));
                }
            }
        });
    }

    // ------------------------------------------------------------------ bridge egg

    void bridgeEgg(Egg egg, Player p) {
        BwPlayer bp = g.data(p);
        if (bp == null || bp.team == null) return;
        eggs.add(new BridgeEgg(egg, bp.team, p.getLocation()));
    }

    @SuppressWarnings("deprecation")
    private void tickEggs() {
        Iterator<BridgeEgg> it = eggs.iterator();
        while (it.hasNext()) {
            BridgeEgg e = it.next();
            if (!e.egg.isValid() || e.ticks++ > 40) { it.remove(); continue; }
            Location l = e.egg.getLocation();
            double dist = l.distance(e.start);
            if (dist > 32) { e.egg.remove(); it.remove(); continue; }
            if (dist < 2.2) continue;
            Block base = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() - 2, l.getBlockZ());
            Vector v = e.egg.getVelocity();
            boolean alongX = Math.abs(v.getX()) > Math.abs(v.getZ());
            boolean any = place(base, e.team);
            if (random.nextInt(3) == 0) any |= place(alongX ? base.getRelative(0, 0, 1) : base.getRelative(1, 0, 0), e.team);
            if (random.nextInt(3) == 0) any |= place(alongX ? base.getRelative(0, 0, -1) : base.getRelative(-1, 0, 0), e.team);
            if (any && e.ticks % 2 == 0) l.getWorld().playSound(l, Sound.CHICKEN_EGG_POP, 1f, 1f);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean place(Block b, BwTeam team) {
        if (b.getType() != Material.AIR || g.placeDenial(b) != null) return false;
        b.setTypeIdAndData(Material.WOOL.getId(), team.color.woolData(), false);
        g.markPlaced(b);
        return true;
    }

    // ------------------------------------------------------------------ mobs

    void spawnBedbug(Location at, Player owner) {
        BwPlayer bp = g.data(owner);
        if (bp == null || bp.team == null) return;
        Silverfish s = at.getWorld().spawn(at.clone().add(0, 0.3, 0), Silverfish.class);
        register(s, owner, bp.team, "Bedbug", 15 * 20);
    }

    void spawnDreamDefender(Location at, Player owner) {
        BwPlayer bp = g.data(owner);
        if (bp == null || bp.team == null) return;
        // not "player created": those golems refuse to target players
        IronGolem golem = at.getWorld().spawn(at, IronGolem.class);
        register(golem, owner, bp.team, "Dream Defender", 240 * 20);
    }

    private void register(LivingEntity e, Player owner, BwTeam team, String label, int ticks) {
        e.setRemoveWhenFarAway(false);
        e.setCustomNameVisible(true);
        OwnedMob m = new OwnedMob(e, owner, team, label, g.tick + ticks, ticks);
        mobs.put(e.getUniqueId(), m);
        updateName(m);
    }

    OwnedMob mob(Entity e) { return e == null ? null : mobs.get(e.getUniqueId()); }

    private void updateName(OwnedMob m) {
        int secs = (int) Math.max(0, (m.expiresAt - g.tick) / 20);
        double frac = m.entity.getHealth() / m.entity.getMaxHealth();
        int bars = (int) Math.ceil(frac * 10);
        StringBuilder bar = new StringBuilder(ChatColor.GREEN.toString());
        for (int i = 0; i < 10; i++) {
            if (i == bars) bar.append(ChatColor.GRAY);
            bar.append('▌');
        }
        m.entity.setCustomName(ChatColor.DARK_GRAY + "[" + ChatColor.YELLOW + net.h2020.bedwars.util.Text.clock(secs)
                + ChatColor.DARK_GRAY + "] " + m.team.color.chat + m.label + " " + bar);
    }

    /** Enemy players a mob may chase. */
    boolean validTarget(OwnedMob m, Entity target) {
        if (target instanceof Player) {
            BwPlayer bp = g.data((Player) target);
            return bp != null && bp.playing() && bp.team != null && bp.team != m.team;
        }
        OwnedMob other = mob(target);
        return other != null && other.team != m.team;
    }

    private void tickMobs() {
        Iterator<OwnedMob> it = mobs.values().iterator();
        while (it.hasNext()) {
            OwnedMob m = it.next();
            if (!m.entity.isValid() || m.entity.isDead()) { it.remove(); continue; }
            if (g.tick >= m.expiresAt) {
                m.entity.getWorld().playEffect(m.entity.getLocation(), Effect.SMOKE, 4);
                m.entity.remove();
                it.remove();
                continue;
            }
            if (g.tick % 10 != 0) continue;
            updateName(m);
            if (m.entity.getLocation().getY() < 0) { m.entity.remove(); it.remove(); continue; }
            if (!(m.entity instanceof Creature)) continue;
            Creature c = (Creature) m.entity;
            LivingEntity cur = c.getTarget();
            if (cur != null && cur.isValid() && validTarget(m, cur)
                    && cur.getLocation().distanceSquared(c.getLocation()) < 24 * 24) continue;
            double range = m.entity instanceof IronGolem ? 20 : 12;
            LivingEntity best = null;
            double bestD = range * range;
            for (BwPlayer bp : g.playingPlayers()) {
                if (bp.team == m.team) continue;
                double d = bp.player.getLocation().distanceSquared(c.getLocation());
                if (d < bestD) { bestD = d; best = bp.player; }
            }
            c.setTarget(best);
        }
    }

    // ------------------------------------------------------------------ sponge

    /** Sponge soaks up nearby water over a few ticks, then pops like on Hypixel. */
    void sponge(final Block b) {
        new BukkitRunnable() {
            int runs;
            @SuppressWarnings("deprecation")
            @Override public void run() {
                if (runs++ > 6 || g.tornDown() || b.getType() != Material.SPONGE) {
                    if (!g.tornDown() && b.getType() == Material.SPONGE) {
                        b.setType(Material.AIR);
                        g.unmarkPlaced(b);
                        b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, Material.SPONGE.getId());
                    }
                    cancel();
                    return;
                }
                int r = Math.min(4, runs + 1);
                for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                    Block w = b.getRelative(x, y, z);
                    if (w.getType() == Material.WATER || w.getType() == Material.STATIONARY_WATER) {
                        w.setType(Material.AIR);
                        g.unmarkPlaced(w);
                    }
                }
            }
        }.runTaskTimer(g.module.plugin(), 1L, 10L);
    }

    // ------------------------------------------------------------------ lifecycle

    void tick() {
        if (!eggs.isEmpty()) tickEggs();
        if (!mobs.isEmpty()) tickMobs();
        if (g.tick % 200 == 0) {
            Iterator<Map.Entry<UUID, TNTPrimed>> it = tntEntities.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, TNTPrimed> e = it.next();
                if (!e.getValue().isValid()) { tntOwners.remove(e.getKey()); it.remove(); }
            }
        }
    }

    void clear() {
        for (OwnedMob m : mobs.values()) m.entity.remove();
        mobs.clear();
        for (BridgeEgg e : eggs) e.egg.remove();
        eggs.clear();
        tntOwners.clear();
        tntEntities.clear();
    }
}
