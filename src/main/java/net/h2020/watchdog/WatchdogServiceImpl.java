package net.h2020.watchdog;

import net.h2020.api.watchdog.CheckType;
import net.h2020.api.watchdog.WatchdogFlagEvent;
import net.h2020.api.watchdog.WatchdogService;
import net.h2020.watchdog.alerts.AlertManager;
import net.h2020.watchdog.alerts.WatchdogLog;
import net.h2020.watchdog.framework.ActionList;
import net.h2020.watchdog.framework.CheckSettings;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.PositionTracker;
import net.h2020.watchdog.framework.TickTask;
import net.h2020.watchdog.framework.Util;
import net.h2020.watchdog.framework.ViolationData;
import net.h2020.watchdog.punish.Announcer;
import net.h2020.watchdog.punish.BanManager;
import net.h2020.watchdog.punish.ReportManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Watchdog framework: per-player per-check violation levels with NCP-style decay, configurable action lists
 * (cancel / log / alert / kick / ban), exemptions with expiry, report priority boost, live debug output and the
 * WatchdogFlagEvent. flag() is thread-safe and may be called from Netty threads.
 */
public final class WatchdogServiceImpl implements WatchdogService, Listener {
    private final JavaPlugin plugin;
    private final WatchdogConfig config;
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<UUID, PlayerData>();
    /** target -> viewers receiving live debug output */
    private final Map<UUID, Set<UUID>> debugViewers = new ConcurrentHashMap<UUID, Set<UUID>>();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();

    private final AlertManager alerts;
    private final WatchdogLog log;
    private final BanManager bans;
    private final Announcer announcer;
    private final ReportManager reports;
    private final PositionTracker tracker = new PositionTracker();

    public WatchdogServiceImpl(JavaPlugin plugin, WatchdogConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.alerts = new AlertManager(plugin, config.consoleAlerts, config.staffAlerts);
        this.log = new WatchdogLog(plugin.getDataFolder());
        this.bans = new BanManager(this);
        this.announcer = new Announcer(this);
        this.reports = new ReportManager(this);
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        TickTask.reset();
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, new TickTask(), 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, tracker, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 600L, 600L));
        registerListener(this);
        registerListener(bans);
        announcer.start();
        log.log("START", "Watchdog enabled (ban-humans=" + config.banHumans + ", exempt-admins=" + config.exemptAdmins + ")");
    }

    public void shutdown() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        for (Listener l : listeners) HandlerList.unregisterAll(l);
        listeners.clear();
        bans.shutdown();
        announcer.stop();
        tracker.clear();
        players.clear();
        log.log("STOP", "Watchdog disabled");
        log.close();
    }

    public void registerListener(Listener l) {
        listeners.add(l);
        Bukkit.getPluginManager().registerEvents(l, plugin);
    }

    public void addTask(BukkitTask t) { tasks.add(t); }

    private void cleanup() {
        for (UUID id : new ArrayList<UUID>(players.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) players.remove(id);
        }
        for (UUID id : new ArrayList<UUID>(debugViewers.keySet())) {
            if (Bukkit.getPlayer(id) == null) debugViewers.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        players.remove(p.getUniqueId());
        tracker.remove(p);
        debugViewers.remove(p.getUniqueId());
        for (Set<UUID> s : debugViewers.values()) s.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ accessors

    public JavaPlugin plugin() { return plugin; }
    public WatchdogConfig config() { return config; }
    public AlertManager alerts() { return alerts; }
    public BanManager bans() { return bans; }
    public Announcer announcer() { return announcer; }
    public ReportManager reports() { return reports; }
    public PositionTracker tracker() { return tracker; }

    public void log(String kind, String msg) { log.log(kind, msg); }

    public PlayerData data(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) {
            d = new PlayerData(p.getUniqueId(), p.getName());
            PlayerData prev = players.putIfAbsent(p.getUniqueId(), d);
            if (prev != null) d = prev;
        }
        return d;
    }

    public PlayerData dataIfPresent(UUID id) { return players.get(id); }

    /** Clear all violation levels (after a ban, /wd reset). */
    public void forget(UUID id) {
        PlayerData d = players.get(id);
        if (d != null) d.resetViolations();
    }

    // ------------------------------------------------------------------ WatchdogService

    @Override
    public boolean flag(Player p, CheckType check, double addVl, String details) {
        if (p == null || check == null) return false;
        CheckSettings cs = config.settings(check);
        if (!cs.enabled || isExempt(p, check)) return false;
        PlayerData d = data(p);
        long now = System.currentTimeMillis();
        boolean reported = d.reportedUntil > now;
        if (reported && addVl > 0) addVl *= config.reportBoost;

        ViolationData v = d.vl(check);
        boolean cancel = false, doLog = false, doAlert = false, doBan = false, doKick = false;
        double vl;
        synchronized (v) {
            v.decay(now, cs);
            v.add(addVl);
            vl = v.vl();
            v.lastFlag = now;
            v.totalFlags++;
            ActionList.Tier tier = cs.actions.tierFor(vl);
            for (ActionList.Action a : tier.actions) {
                switch (a.kind) {
                    case CANCEL: cancel = true; break;
                    case LOG: if (v.ready(a, cs.actions.actionCount(), now)) doLog = true; break;
                    case ALERT: if (v.ready(a, cs.actions.actionCount(), now)) doAlert = true; break;
                    case KICK: if (v.ready(a, cs.actions.actionCount(), now)) doKick = true; break;
                    case BAN: doBan = true; break;
                    default: break;
                }
            }
        }
        String det = details == null ? "" : details;
        fireFlagEvent(p, check, vl, det);
        if (doLog) {
            log.log("FLAG", p.getName() + " failed " + check.name() + " (vl " + Util.fmt1(vl) + ", +" + Util.fmt(addVl) + ") "
                    + det + " ping=" + Util.ping(p) + (cancel ? " [cancel]" : "") + (reported ? " [reported]" : "")
                    + (Util.isBot(p) ? " [bot]" : ""));
        }
        if (doAlert) alerts.flagAlert(p.getName(), check, vl, det, reported);
        debug(p, check, ChatColor.RED + "FLAG +" + Util.fmt(addVl) + " -> vl " + Util.fmt1(vl) + ChatColor.GRAY + " " + det
                + (cancel ? ChatColor.RED + " [cancel]" : ""));
        if (doBan && !bans.isPending(p.getUniqueId())) bans.schedule(p, check.name());
        if (doKick) {
            final Player fp = p;
            // Always on the next tick: flag() may run inside the player's own packet handling (PlayerMoveEvent, a
            // bot's packet flush), and disconnecting mid-handler leaves the connection half processed.
            Runnable kick = () -> { if (fp.isOnline()) fp.kickPlayer(ChatColor.RED + "You have been kicked!"); };
            try { Bukkit.getScheduler().runTask(plugin, kick); } catch (Exception ignored) {} // disabling
        }
        return cancel;
    }

    /** NCP-style reward for a legit action (e.g. reach vl *= 0.95 on a valid hit). */
    public void reward(Player p, CheckType check, double factor) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) return;
        ViolationData v = d.vlIfPresent(check);
        if (v == null) return;
        synchronized (v) {
            v.decay(System.currentTimeMillis(), config.settings(check));
            v.multiply(factor);
        }
    }

    @Override
    public double violationLevel(Player p, CheckType check) {
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) return 0;
        ViolationData v = d.vlIfPresent(check);
        if (v == null) return 0;
        synchronized (v) { return v.decay(System.currentTimeMillis(), config.settings(check)); }
    }

    @Override
    public void exempt(Player p, CheckType check, long durationMs) {
        if (p == null || check == null) return;
        PlayerData d = data(p);
        long until = System.currentTimeMillis() + durationMs;
        Long cur = d.exemptions().get(check);
        if (cur == null || cur < until) d.exemptions().put(check, until);
    }

    /** Exempt from every check of a group. */
    public void exemptGroup(Player p, CheckType.Group group, long durationMs) {
        for (CheckType t : CheckType.values()) if (t.group == group) exempt(p, t, durationMs);
    }

    public void unexempt(Player p) {
        PlayerData d = players.get(p.getUniqueId());
        if (d != null) d.exemptions().clear();
    }

    @Override
    public boolean isExempt(Player p, CheckType check) {
        if (p == null) return true;
        PlayerData d = players.get(p.getUniqueId());
        if (d == null) return false;
        Long until = d.exemptions().get(check);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        d.exemptions().remove(check);
        return false;
    }

    @Override
    public void ban(Player p, String reasonCode) { bans.ban(p, reasonCode == null ? "WATCHDOG" : reasonCode); }

    @Override
    public int bansLast7Days() { return bans.bansSinceBoot(); }

    // ------------------------------------------------------------------ events / debug

    private void fireFlagEvent(final Player p, final CheckType check, final double vl, final String details) {
        if (WatchdogFlagEvent.getHandlerList().getRegisteredListeners().length == 0) return;
        runMain(() -> Bukkit.getPluginManager().callEvent(new WatchdogFlagEvent(p, check, vl, details)));
    }

    public void runMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) { r.run(); return; }
        try { Bukkit.getScheduler().runTask(plugin, r); } catch (Exception ignored) {} // disabling
    }

    /** @return true if debug output is now on */
    public boolean toggleDebug(Player viewer, Player target) {
        Set<UUID> set = debugViewers.get(target.getUniqueId());
        if (set == null) {
            set = ConcurrentHashMap.newKeySet();
            Set<UUID> prev = debugViewers.putIfAbsent(target.getUniqueId(), set);
            if (prev != null) set = prev;
        }
        if (set.remove(viewer.getUniqueId())) return false;
        set.add(viewer.getUniqueId());
        return true;
    }

    public boolean debugging(Player target) {
        Set<UUID> s = debugViewers.get(target.getUniqueId());
        return s != null && !s.isEmpty();
    }

    /** Live per-check output to staff watching this player (/wd debug). Cheap no-op when nobody watches. */
    public void debug(Player target, CheckType check, String message) {
        final Set<UUID> s = debugViewers.get(target.getUniqueId());
        if (s == null || s.isEmpty()) return;
        final String line = ChatColor.DARK_GRAY + "[WD-DBG] " + ChatColor.GRAY + target.getName() + " " + ChatColor.YELLOW
                + check.name() + ChatColor.GRAY + " " + message;
        runMain(() -> {
            for (UUID id : s) {
                Player v = Bukkit.getPlayer(id);
                if (v != null) v.sendMessage(line);
            }
        });
    }
}
