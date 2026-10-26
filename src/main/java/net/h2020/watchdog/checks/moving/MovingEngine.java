package net.h2020.watchdog.checks.moving;

import net.h2020.api.packet.PacketHub;
import net.h2020.api.watchdog.CheckType;
import net.h2020.api.watchdog.WatchdogService;
import net.h2020.watchdog.checks.net.FlagDispatcher;
import net.h2020.watchdog.checks.net.NetChecks;
import net.h2020.watchdog.checks.net.PacketClock;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.GenericAttributes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Runtime of the moving + net check groups: per-player data, the PacketHub listener (packet thread), the Bukkit
 * listener (PlayerMoveEvent drives evaluation of the packet that caused it) and the tick task (evaluates ticks the
 * server processed without a PlayerMoveEvent, hover, grace windows, packet-thread set-backs).
 */
final class MovingEngine {
    final WatchdogService service;
    final JavaPlugin plugin;
    final MovingConfig config;
    final FlagDispatcher dispatcher;
    final NetChecks net;
    final MoveProcessor processor;
    private final ConcurrentHashMap<UUID, MovingData> data = new ConcurrentHashMap<>();
    private MovingListener listener;
    private MovingPacketListener packets;
    private BukkitTask task;
    private long lastErrorLog;

    MovingEngine(WatchdogService service, JavaPlugin plugin) {
        this.service = service;
        this.plugin = plugin;
        this.config = MovingConfig.load(plugin);
        this.dispatcher = new FlagDispatcher(service);
        this.net = new NetChecks(dispatcher, new NetChecks.Setback() {
            @Override public void request(Player p, CheckType cause) {
                MovingData d = get(p);
                if (d != null) d.setbackRequested = true;
            }
        });
        for (CheckType t : CheckType.values()) {
            if (t.group == CheckType.Group.NET) net.setEnabled(t, config.enabled(t));
        }
        this.processor = new MoveProcessor(this);
    }

    void start() {
        for (Player p : Bukkit.getOnlinePlayers()) create(p, true);
        listener = new MovingListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        packets = new MovingPacketListener(this);
        PacketHub.register(packets);
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { tick(); }
        }, 1L, 1L);
    }

    void stop() {
        if (task != null) task.cancel();
        if (packets != null) PacketHub.unregister(packets);
        if (listener != null) HandlerList.unregisterAll(listener);
        dispatcher.clear();
        data.clear();
    }

    // ------------------------------------------------------------------ data

    MovingData get(Player p) { return p == null ? null : data.get(p.getUniqueId()); }

    MovingData create(Player p, boolean awaitTeleport) {
        MovingData d = new MovingData(p.getUniqueId(), p.getName());
        Location l = p.getLocation();
        d.resetPhysics(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
        d.setBack = l.clone();
        d.awaitTeleport = awaitTeleport;
        d.awaitSince = System.currentTimeMillis();
        d.inSeq = 0;
        d.prevTickSeq = 0;
        d.leniencyTicks = 3;
        if (awaitTeleport) d.teleports.add(l.getX(), l.getY(), l.getZ());
        d.flyGraceUntil = System.currentTimeMillis() + 2000;
        data.put(p.getUniqueId(), d);
        return d;
    }

    void remove(Player p) { data.remove(p.getUniqueId()); }

    // ------------------------------------------------------------------ violations

    boolean active(Player p, CheckType t) {
        return config.enabled(t) && !service.isExempt(p, t);
    }

    /** Main thread. @return true if the framework wants a cancel / set-back. */
    boolean flag(Player p, CheckType t, double vl, String details) {
        if (!config.enabled(t)) return false;
        MovingData d = get(p);
        if (d != null && d.debug) p.sendMessage("§c[WD] §7" + t.name().toLowerCase() + " §f+" + String.format("%.2f", vl) + " §7" + details);
        try {
            return service.flag(p, t, vl, details);
        } catch (Throwable ex) {
            logError("flag " + t, ex);
            return false;
        }
    }

    /** Any thread. Cancel turns into a set-back on the next tick. */
    void flagAsync(Player p, CheckType t, double vl, String details, boolean setbackOnCancel) {
        if (!config.enabled(t)) return;
        dispatcher.flag(p, t, vl, details, setbackOnCancel ? new FlagDispatcher.OnCancel() {
            @Override public void run(Player pl) { requestSetback(pl, get(pl)); }
        } : null);
    }

    void requestSetback(Player p, MovingData d) {
        if (d != null) d.setbackRequested = true;
    }

    // ------------------------------------------------------------------ set-backs / teleports

    /** Marks our own set-back: moves are ignored until the client confirms it, fall distance is kept (NCP). */
    void prepareSetback(MovingData d, Location sb, long now) {
        d.expectedSetback = sb.clone();
        d.awaitTeleport = true;
        d.awaitSince = now;
        d.teleports.add(sb.getX(), sb.getY(), sb.getZ());
        d.resetPhysics(sb.getWorld().getName(), sb.getX(), sb.getY(), sb.getZ());
        d.setBack = sb.clone();
    }

    private Location setbackLocation(Player p, MovingData d) {
        Location sb = d.setBack != null && p.getWorld().equals(d.setBack.getWorld()) ? d.setBack.clone() : p.getLocation();
        Location cur = p.getLocation();
        sb.setYaw(cur.getYaw());
        sb.setPitch(cur.getPitch());
        return sb;
    }

    /** -Dh2020.wddebug=true logs every set-back to the console. */
    static final boolean SETBACK_DEBUG = Boolean.getBoolean("h2020.wddebug");

    static void logSetback(Player p, Location sb, String why) {
        if (!SETBACK_DEBUG || sb == null) return;
        Location c = p.getLocation();
        Bukkit.getLogger().info(String.format("[wddebug] setback %s (%s) %.1f,%.1f,%.1f -> %.1f,%.1f,%.1f", p.getName(), why,
                c.getX(), c.getY(), c.getZ(), sb.getX(), sb.getY(), sb.getZ()));
    }

    private void teleportSetback(Player p, MovingData d, Location sb) {
        logSetback(p, sb, "requested");
        prepareSetback(d, sb, System.currentTimeMillis());
        p.teleport(sb, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    void onTeleport(PlayerTeleportEvent ev) {
        Player p = ev.getPlayer();
        MovingData d = get(p);
        Location to = ev.getTo();
        if (d == null || to == null || to.getWorld() == null) return;
        if (SETBACK_DEBUG) Bukkit.getLogger().info(String.format("[wddebug] teleport %s %s -> %.1f,%.1f,%.1f cancelled=%s",
                p.getName(), ev.getCause(), to.getX(), to.getY(), to.getZ(), ev.isCancelled()));
        Location exp = d.expectedSetback;
        boolean own = exp != null && exp.getWorld() != null && exp.getWorld().equals(to.getWorld())
                && Math.abs(exp.getX() - to.getX()) < 1e-6 && Math.abs(exp.getY() - to.getY()) < 1e-6
                && Math.abs(exp.getZ() - to.getZ()) < 1e-6;
        d.expectedSetback = null;
        d.teleports.add(to.getX(), to.getY(), to.getZ());
        d.awaitTeleport = true;
        d.awaitSince = System.currentTimeMillis();
        d.resetPhysics(to.getWorld().getName(), to.getX(), to.getY(), to.getZ());
        if (!own) {
            d.setBack = to.clone();
            // Pearls and plugin teleports reset the fall (EntityEnderPearl sets fallDistance = 0); the server's own
            // corrections (UNKNOWN) keep it, like vanilla.
            if (ev.getCause() != PlayerTeleportEvent.TeleportCause.UNKNOWN) d.resetNoFall();
            if (!to.getWorld().equals(ev.getFrom().getWorld())) {
                d.velocity.clear();
                d.ghosts.clear();
            }
        }
    }

    void onRespawn(Player p, Location loc) {
        MovingData d = get(p);
        if (d == null || loc == null || loc.getWorld() == null) return;
        d.resetPhysics(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        // The exact respawn position arrives with the S08 that follows (outbound hook); until then wait.
        d.awaitTeleport = true;
        d.awaitSince = System.currentTimeMillis();
        d.setBack = loc.clone();
        d.expectedSetback = null;
        d.resetNoFall();
        d.velocity.clear();
        d.ghosts.clear();
        d.leniencyTicks = Math.max(d.leniencyTicks, 2);
        d.hBuffer = MovingData.HBUF_MAX;
        d.vBuffer = MovingData.VBUF_MAX;
        d.net.resetRequested = true;
    }

    void lenient(Player p, int ticks) {
        MovingData d = get(p);
        if (d == null) return;
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        processor.resync(p, h, d, ticks);
    }

    void flightGrace(Player p) {
        MovingData d = get(p);
        if (d == null) return;
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        d.flyGraceUntil = Math.max(d.flyGraceUntil, System.currentTimeMillis() + 1000L + 2L * Math.max(0, Math.min(1000, h.ping)));
    }

    // ------------------------------------------------------------------ PlayerMoveEvent

    void onMove(PlayerMoveEvent ev) {
        Player p = ev.getPlayer();
        MovingData d = get(p);
        if (d == null) return;
        Location to = ev.getTo();
        if (to == null) return;
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        List<FlyingEntry> batch = popUntilMatch(d, to, h);
        if (batch == null) return; // not caused by a client packet we saw (plugin-fired or modified earlier)
        if (ev.isCancelled()) {
            // Frozen by another module: the server sends the player back to 'from' and skips the fall handling.
            Location from = ev.getFrom();
            d.resetPhysics(from.getWorld().getName(), from.getX(), from.getY(), from.getZ());
            d.teleports.add(from.getX(), from.getY(), from.getZ());
            d.awaitTeleport = true;
            d.awaitSince = System.currentTimeMillis();
            d.noFallDist = h.fallDistance;
            return;
        }
        Location sb = processor.process(p, d, batch, true);
        if (sb != null) { logSetback(p, sb, "move"); ev.setTo(sb); }
    }

    private static boolean matches(FlyingEntry e, Location to, EntityPlayer h) {
        if (e.ignored && !e.confirm) {
            // The server ignores these too, but they may still be at the head of the queue.
            return false;
        }
        if (e.hasPos) {
            if (e.x != to.getX() || e.y != to.getY() || e.z != to.getZ()) return false;
        } else {
            if (!e.hasLook) return false;
            if (to.getX() != h.locX || to.getY() != h.locY || to.getZ() != h.locZ) return false;
        }
        if (e.hasLook && (e.yaw != to.getYaw() || e.pitch != to.getPitch())) return false;
        return true;
    }

    private static List<FlyingEntry> popUntilMatch(MovingData d, Location to, EntityPlayer h) {
        int n = 0;
        boolean found = false;
        for (FlyingEntry e : d.queue) {
            n++;
            if (matches(e, to, h)) { found = true; break; }
            if (n >= 800) break;
        }
        if (!found) return null;
        List<FlyingEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            FlyingEntry e = d.queue.poll();
            if (e == null) break;
            d.queueSize.decrementAndGet();
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ tick task

    private void tick() {
        PacketClock.tick();
        dispatcher.drain();
        long now = System.currentTimeMillis();
        long serverTick = PacketClock.serverTicks();
        for (Player p : Bukkit.getOnlinePlayers()) {
            MovingData d = get(p);
            if (d == null) d = create(p, false);
            try {
                tickPlayer(p, d, now, serverTick);
            } catch (Throwable t) {
                logError("moving tick failed for " + p.getName(), t);
            }
        }
    }

    private void tickPlayer(Player p, MovingData d, long now, long serverTick) {
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        int ping = Math.max(0, Math.min(1000, h.ping));
        d.pingSnapshot = ping;

        // 1. Ticks the server already processed without firing PlayerMoveEvent (tiny moves, C03, ignored).
        //    Entries counted two scheduler phases ago are guaranteed to have run through PlayerConnection.
        long limit = d.prevTickSeq2;
        d.prevTickSeq2 = d.prevTickSeq;
        List<FlyingEntry> batch = null;
        FlyingEntry head;
        while ((head = d.queue.peek()) != null && head.seq <= limit) {
            d.queue.poll();
            d.queueSize.decrementAndGet();
            if (batch == null) batch = new ArrayList<>(4);
            batch.add(head);
        }
        if (batch != null) {
            Location sb = processor.process(p, d, batch, false);
            if (sb != null) { logSetback(p, sb, "tick"); p.teleport(sb, PlayerTeleportEvent.TeleportCause.UNKNOWN); }
        }
        d.prevTickSeq = d.inSeq;

        // 2. Set-backs requested by packet-thread checks (Timer, MorePackets) or the hover check.
        if (d.setbackRequested) {
            d.setbackRequested = false;
            if (!p.isDead() && !p.isInsideVehicle()) teleportSetback(p, d, setbackLocation(p, d));
        }

        // 3. Grace windows: the client sees potion/attribute/ability changes one round trip later.
        updateGrace(p, h, d, now, ping);

        // 4. Hover.
        HoverCheck.tick(this, p, h, ((CraftWorld) p.getWorld()).getHandle(), d, now);

        // 5. Housekeeping.
        if ((serverTick & 31) == 0) d.ghosts.cleanup(now);
        if (d.queueSize.get() > 1200) {
            d.clearQueue();
            processor.resync(p, h, d, 5);
        }
    }

    private void updateGrace(Player p, EntityPlayer h, MovingData d, long now, int ping) {
        long grace = 1000L + 2L * ping;
        int speed = -1, jump = -1;
        for (PotionEffect pe : p.getActivePotionEffects()) {
            if (pe.getType().equals(PotionEffectType.SPEED)) speed = Math.max(speed, pe.getAmplifier());
            else if (pe.getType().equals(PotionEffectType.JUMP)) jump = Math.max(jump, pe.getAmplifier());
        }
        if (speed >= d.speedAmp) { d.speedAmp = speed; d.speedUntil = now + grace; }
        else if (now > d.speedUntil) d.speedAmp = speed;
        if (jump >= d.jumpAmp) { d.jumpAmp = jump; d.jumpUntil = now + grace; }
        else if (now > d.jumpUntil) d.jumpAmp = jump;

        double base = h.abilities.walkSpeed;
        try {
            base = Math.max(base, h.getAttributeInstance(GenericAttributes.MOVEMENT_SPEED).b());
        } catch (Throwable ignored) {
            // keep abilities value
        }
        if (base >= d.walkBase) { d.walkBase = base; d.walkUntil = now + grace; }
        else if (now > d.walkUntil) d.walkBase = base;

        GameMode gm = p.getGameMode();
        if (p.getAllowFlight() || p.isFlying() || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || now - d.lastClientFlyClaim < grace) {
            d.flyGraceUntil = Math.max(d.flyGraceUntil, now + grace);
        }
    }

    // ------------------------------------------------------------------ misc

    void setDebug(Player p, boolean on) {
        MovingData d = get(p);
        if (d != null) d.debug = on;
    }

    void debugMove(Player p, MoveContext c) {
        String line = String.format("[mv] h %.4f/%.4f y %+.4f %s%s%s v%d spr=%s snk=%s in=%.2f acc=%.4f slip=%.3f pos=%s",
                c.hDist, c.data.nextHBase, c.dy, c.from.ground ? "G" : "A", c.to.ground ? "G" : "A",
                c.flyingMode ? " fly" : "", c.velocities.size(), c.sprinting, c.sneaking, c.inputMag,
                c.horizontalAccel(), c.to.slip, c.hasPos);
        p.sendMessage("§8" + line);
        // mirrored to the console so bot movement can be inspected too
        plugin.getLogger().info(p.getName() + " " + line);
    }

    void logError(String what, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog < 10_000) return;
        lastErrorLog = now;
        plugin.getLogger().log(Level.WARNING, "[Watchdog] " + what, t);
    }
}
