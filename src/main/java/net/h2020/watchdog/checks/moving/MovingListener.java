package net.h2020.watchdog.checks.moving;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;

/** Bukkit side of the moving checks (main thread). */
final class MovingListener implements Listener {
    private final MovingEngine eng;

    MovingListener(MovingEngine eng) { this.eng = eng; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent ev) {
        eng.create(ev.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent ev) {
        eng.remove(ev.getPlayer());
    }

    /** HIGHEST: sees cancels/changes of game modules (NORMAL), set-backs are visible to MONITOR listeners. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent ev) {
        eng.onMove(ev);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent ev) {
        eng.onTeleport(ev);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent ev) {
        eng.onRespawn(ev.getPlayer(), ev.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent ev) {
        MovingData d = eng.get(ev.getEntity());
        if (d != null) {
            d.resetNoFall();
            d.net.resetRequested = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent ev) {
        Player p = ev.getPlayer();
        MovingData d = eng.get(p);
        if (d == null) return;
        Vector v = ev.getVelocity();
        // Same value the S12 will carry (dedup'd against the outbound packet).
        d.velocity.add(false, v.getX(), v.getY(), v.getZ(), d.inSeq);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent ev) {
        eng.flightGrace(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleFlight(PlayerToggleFlightEvent ev) {
        eng.flightGrace(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent ev) {
        if (ev.getEntered() instanceof Player) eng.lenient((Player) ev.getEntered(), 5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent ev) {
        if (ev.getExited() instanceof Player) eng.lenient((Player) ev.getExited(), 10);
    }
}
