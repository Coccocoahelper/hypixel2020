package net.h2020.bots.ai;

import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.GameBrain;
import net.h2020.bots.ai.perception.PlayerStats;
import net.h2020.bots.ai.perception.SoundBus;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Feeds the AI: sounds, combat events, kills, game state changes, and brain (re)assignment triggers. */
final class AIListener implements Listener {

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            Object s = ((Projectile) damager).getShooter();
            if (s instanceof Player) return (Player) s;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker == victim) return;
        if (e.getDamager() instanceof Player) PlayerStats.hit(attacker, attacker.getEyeLocation().distance(victim.getLocation().add(0, 0.9, 0)) - 0.3);
        SoundBus.emit(SoundBus.Kind.HIT, victim.getLocation(), attacker);
        BaseBrain vb = BotAI.brainOf(victim);
        if (vb != null) vb.onHurt(attacker, e.getFinalDamage());
        BaseBrain ab = BotAI.brainOf(attacker);
        if (ab != null) ab.onHitLanded(victim);
        if (ab != null && ab.context() != null && e.getDamager() instanceof Projectile)
            ab.context().combat.onProjectileHit(e.getDamager().getType().name().toLowerCase());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent || !(e.getEntity() instanceof Player)) return;
        BaseBrain vb = BotAI.brainOf((Player) e.getEntity());
        if (vb != null) vb.onHurt(null, e.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) { PlayerStats.swing(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { SoundBus.emit(SoundBus.Kind.PLACE, e.getBlock().getLocation().add(0.5, 0.5, 0.5), e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { SoundBus.emit(SoundBus.Kind.BREAK, e.getBlock().getLocation().add(0.5, 0.5, 0.5), e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player) SoundBus.emit(SoundBus.Kind.BOW, e.getEntity().getLocation(), (Player) e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl) {
            Object s = e.getEntity().getShooter();
            if (s instanceof Player) SoundBus.emit(SoundBus.Kind.PEARL, ((Player) s).getLocation(), (Player) s);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { SoundBus.emit(SoundBus.Kind.EXPLOSION, e.getLocation(), null); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) { SoundBus.emit(SoundBus.Kind.EAT, e.getPlayer().getLocation(), e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(GameKillEvent e) {
        Player victim = e.victim();
        boolean voided = victim != null && (victim.getLocation().getY() < 2
                || (victim.getLastDamageCause() != null && victim.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.VOID));
        BaseBrain vb = BotAI.brainOf(victim);
        if (vb != null && vb.context() != null) vb.context().combat.onDeath(voided);
        if (vb != null) vb.onDeath(e.killer(), voided);
        BaseBrain kb = BotAI.brainOf(e.killer());
        if (kb != null && e.killer() != victim) kb.onKill(victim, e.finalKill());
        for (BaseBrain b : BotAI.brains()) {
            if (b instanceof GameBrain && ((GameBrain) b).game() == e.game()) ((GameBrain) b).onAnyKill(e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(GameStateChangeEvent e) {
        for (BaseBrain b : BotAI.brains()) {
            if (b instanceof GameBrain && ((GameBrain) b).game() == e.game()) {
                try { ((GameBrain) b).onGameState(e.from(), e.to()); } catch (Throwable ignored) {}
            }
        }
        BotAI.reconcileGame(e.game());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { BotAI.reconcileSoon(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 64) {
            BaseBrain b = BotAI.brainOf(e.getPlayer());
            if (b != null) b.onTeleported();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        PlayerStats.forget(e.getPlayer());
        BotAI.forgetPersona(e.getPlayer().getUniqueId());
    }
}
