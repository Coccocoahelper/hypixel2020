package net.h2020.core.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Melee knockback between players (and bots), tuned toward Hypixel's 2020 feel: a bit less height and distance
 * than vanilla 1.8, same sprint-hit bonus structure. Uses the vanilla 1.8 formula (EntityLiving.a + the sprint /
 * Knockback-enchant extra in EntityHuman.attack) with configurable constants, replacing the velocity the server
 * is about to send in the same tick (PlayerVelocityEvent), so Watchdog's anti-knockback check sees the real value.
 * Section core.knockback in config.yml; enabled: false restores vanilla.
 */
public final class Knockback implements Listener {
    public boolean enabled = true;
    public double friction = 2.0;
    public double horizontal = 0.36;
    public double vertical = 0.36;
    public double verticalLimit = 0.4;
    public double extraHorizontal = 0.45;
    public double extraVertical = 0.085;

    /** A melee hit seen this tick: victim -> the data the vanilla formula needs. */
    private final Map<UUID, Hit> pending = new HashMap<>();

    private static final class Hit {
        final Player attacker;
        final Vector before;
        final int extra;
        final long tick;
        Hit(Player attacker, Vector before, int extra, long tick) {
            this.attacker = attacker; this.before = before; this.extra = extra; this.tick = tick;
        }
    }

    public void load(ConfigurationSection s) {
        if (s == null) return;
        enabled = s.getBoolean("enabled", enabled);
        friction = Math.max(1.0, s.getDouble("friction", friction));
        horizontal = s.getDouble("horizontal", horizontal);
        vertical = s.getDouble("vertical", vertical);
        verticalLimit = s.getDouble("vertical-limit", verticalLimit);
        extraHorizontal = s.getDouble("extra-horizontal", extraHorizontal);
        extraVertical = s.getDouble("extra-vertical", extraVertical);
    }

    private static long tick(Player p) { return p.getWorld().getFullTime(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!enabled || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) return;
        Player victim = (Player) e.getEntity(), attacker = (Player) e.getDamager();
        ItemStack hand = attacker.getItemInHand();
        int extra = hand == null ? 0 : hand.getEnchantmentLevel(Enchantment.KNOCKBACK);
        if (attacker.isSprinting()) extra++;   // still set here; vanilla clears it after the hit
        pending.put(victim.getUniqueId(), new Hit(attacker, victim.getVelocity(), extra, tick(victim)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Hit h = pending.remove(e.getPlayer().getUniqueId());
        if (!enabled || h == null || h.tick != tick(e.getPlayer()) || !h.attacker.isValid()) return;
        Player victim = e.getPlayer();
        double dx = h.attacker.getLocation().getX() - victim.getLocation().getX();
        double dz = h.attacker.getLocation().getZ() - victim.getLocation().getZ();
        while (dx * dx + dz * dz < 1.0E-4) {   // vanilla: random direction when standing inside each other
            dx = (Math.random() - Math.random()) * 0.01;
            dz = (Math.random() - Math.random()) * 0.01;
        }
        double dist = Math.sqrt(dx * dx + dz * dz);
        double x = h.before.getX() / friction - dx / dist * horizontal;
        double y = Math.min(verticalLimit, h.before.getY() / friction + vertical);
        double z = h.before.getZ() / friction - dz / dist * horizontal;
        if (h.extra > 0) {
            double yaw = Math.toRadians(h.attacker.getLocation().getYaw());
            x += -Math.sin(yaw) * h.extra * extraHorizontal;
            y += extraVertical;
            z += Math.cos(yaw) * h.extra * extraHorizontal;
        }
        e.setVelocity(new Vector(x, y, z));
    }
}
