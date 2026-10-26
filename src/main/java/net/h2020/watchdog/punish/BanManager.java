package net.h2020.watchdog.punish;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.api.watchdog.WatchdogBanEvent;
import net.h2020.watchdog.WatchdogConfig;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hypixel-2020-style Watchdog ban flow. Crossing a check's ban tier does not ban instantly: the ban is scheduled
 * 5-40 seconds later (the "ban wave" feel), or held until the end of the player's current game. Humans get the
 * temp-ban screen (persisted in bans.yml only if watchdog.ban-humans), bots fire WatchdogBanEvent and are removed.
 */
public final class BanManager implements Listener {
    private final WatchdogServiceImpl service;
    private final File file;
    private final Map<UUID, BanRecord> bans = new ConcurrentHashMap<UUID, BanRecord>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<UUID, Pending>();
    private final Map<UUID, Long> exemptNotified = new ConcurrentHashMap<UUID, Long>();
    private final AtomicInteger bansSinceBoot = new AtomicInteger();
    private final Random random = new Random();

    private static final class Pending {
        final UUID uuid;
        final String name;
        final String reason;
        final boolean bot;
        String gameId;
        BukkitTask task;

        Pending(UUID uuid, String name, String reason, boolean bot) {
            this.uuid = uuid; this.name = name; this.reason = reason; this.bot = bot;
        }
    }

    public BanManager(WatchdogServiceImpl service) {
        this.service = service;
        this.file = new File(service.plugin().getDataFolder(), "bans.yml");
        load();
    }

    private WatchdogConfig cfg() { return service.config(); }

    // ------------------------------------------------------------------ persistence

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("bans");
        if (s == null) return;
        long now = System.currentTimeMillis();
        for (String key : s.getKeys(false)) {
            try {
                ConfigurationSection b = s.getConfigurationSection(key);
                BanRecord r = new BanRecord(UUID.fromString(key), b.getString("name", "?"), b.getString("id", "00000000"),
                        b.getString("reason", "WATCHDOG"), b.getLong("created"), b.getLong("expires"));
                if (r.active(now)) bans.put(r.uuid, r);
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        final YamlConfiguration y = new YamlConfiguration();
        for (BanRecord r : bans.values()) {
            String k = "bans." + r.uuid;
            y.set(k + ".name", r.name);
            y.set(k + ".id", r.banId);
            y.set(k + ".reason", r.reasonCode);
            y.set(k + ".created", r.created);
            y.set(k + ".expires", r.expires);
        }
        final String data = y.saveToString();
        Runnable write = () -> {
            try {
                File dir = file.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                java.nio.file.Files.write(file.toPath(), data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                service.plugin().getLogger().warning("[Watchdog] could not save bans.yml: " + e.getMessage());
            }
        };
        if (service.plugin().isEnabled()) {
            try { Bukkit.getScheduler().runTaskAsynchronously(service.plugin(), write); return; } catch (Exception ignored) {}
        }
        write.run();
    }

    // ------------------------------------------------------------------ scheduling

    /** Called when a check reaches its ban tier. Any thread. */
    public void schedule(final Player p, final String reasonCode) {
        if (Bukkit.isPrimaryThread()) scheduleMain(p, reasonCode);
        else service.runMain(() -> scheduleMain(p, reasonCode));
    }

    private void scheduleMain(Player p, String reasonCode) {
        if (p == null || !p.isOnline() || pending.containsKey(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        if (!Util.isBot(p) && cfg().exemptAdmins && Util.isAdmin(p)) {
            Long last = exemptNotified.get(p.getUniqueId());
            if (last == null || now - last > 60000) {
                exemptNotified.put(p.getUniqueId(), now);
                service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + p.getName() + ChatColor.WHITE
                        + " reached the ban threshold of " + ChatColor.RED + reasonCode + ChatColor.GRAY + " (admin exempt, not banned)");
                service.log("BAN-EXEMPT", p.getName() + " reached ban tier of " + reasonCode + " (admin exempt)");
            }
            return;
        }
        final Pending pd = new Pending(p.getUniqueId(), p.getName(), reasonCode, Util.isBot(p));
        pending.put(pd.uuid, pd);
        Game g = gameOf(p);
        if (g != null && g.state() == GameState.PLAYING && random.nextDouble() < cfg().endOfGameBanChance) {
            pd.gameId = g.id();
            pd.task = Bukkit.getScheduler().runTaskLater(service.plugin(), () -> fire(pd), cfg().endOfGameMaxWaitSeconds * 20L);
            service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + p.getName() + ChatColor.WHITE
                    + " flagged for ban " + ChatColor.RED + reasonCode + ChatColor.GRAY + " (end of game " + g.id() + ")");
            service.log("BAN-QUEUED", p.getName() + " " + reasonCode + " at end of game " + g.id());
        } else {
            int min = cfg().banDelayMinSeconds, max = cfg().banDelayMaxSeconds;
            int delay = min + (max > min ? random.nextInt(max - min + 1) : 0);
            pd.task = Bukkit.getScheduler().runTaskLater(service.plugin(), () -> fire(pd), delay * 20L);
            service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + p.getName() + ChatColor.WHITE
                    + " flagged for ban " + ChatColor.RED + reasonCode + ChatColor.GRAY + " (in " + delay + "s)");
            service.log("BAN-QUEUED", p.getName() + " " + reasonCode + " in " + delay + "s");
        }
    }

    private void fire(Pending pd) {
        if (pending.remove(pd.uuid) == null) return;
        Player p = Bukkit.getPlayer(pd.uuid);
        if (p != null && p.isOnline()) ban(p, pd.reason);
        else offlineBan(pd.uuid, pd.name, pd.reason, pd.bot);
    }

    public boolean isPending(UUID id) { return pending.containsKey(id); }

    public void cancelPending(UUID id) {
        Pending pd = pending.remove(id);
        if (pd != null && pd.task != null) pd.task.cancel();
    }

    // ------------------------------------------------------------------ execution

    /** Immediate ban on the main thread (WatchdogService.ban). */
    public void ban(final Player p, final String reasonCode) {
        if (!Bukkit.isPrimaryThread()) {
            service.runMain(() -> ban(p, reasonCode));
            return;
        }
        if (p == null) return;
        cancelPending(p.getUniqueId());
        if (!p.isOnline()) { offlineBan(p.getUniqueId(), p.getName(), reasonCode, Util.isBot(p)); return; }
        boolean bot = Util.isBot(p);
        if (!bot && cfg().exemptAdmins && Util.isAdmin(p)) {
            p.sendMessage(ChatColor.RED + "[WD] " + ChatColor.GRAY + "You would have been banned for " + ChatColor.RED
                    + reasonCode + ChatColor.GRAY + " (admins are exempt).");
            service.log("BAN-EXEMPT", p.getName() + " " + reasonCode);
            return;
        }
        Bukkit.getPluginManager().callEvent(new WatchdogBanEvent(p, reasonCode));
        bansSinceBoot.incrementAndGet();
        String banId = BanScreen.newBanId();
        long now = System.currentTimeMillis();
        long length = cfg().banDays * 86400000L;

        // The game modules announce "A player has been removed from your game." from WatchdogBanEvent.
        thankReporters(p);
        service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + p.getName() + ChatColor.WHITE + " was banned "
                + ChatColor.GRAY + "(" + reasonCode + ") " + ChatColor.DARK_GRAY + "#" + banId + (bot ? " [bot]" : ""));
        service.log("BAN", p.getName() + " (" + p.getUniqueId() + ") " + reasonCode + " #" + banId + (bot ? " bot" : "")
                + (bot || cfg().banHumans ? "" : " (kick only, ban-humans=false)"));
        service.forget(p.getUniqueId());

        if (bot) {
            Bot b = Util.bot(p);
            if (b != null && !b.isRemoved()) { b.remove(); return; }
            p.kickPlayer(BanScreen.screen(length - 1000, banId));
            return;
        }
        if (cfg().banHumans) {
            bans.put(p.getUniqueId(), new BanRecord(p.getUniqueId(), p.getName(), banId, reasonCode, now, now + length));
            save();
        }
        p.kickPlayer(BanScreen.screen(length - 1000, banId));
    }

    private void offlineBan(UUID uuid, String name, String reasonCode, boolean bot) {
        bansSinceBoot.incrementAndGet();
        String banId = BanScreen.newBanId();
        long now = System.currentTimeMillis();
        boolean persist = cfg().banHumans && !bot;
        if (persist) {
            bans.put(uuid, new BanRecord(uuid, name, banId, reasonCode, now, now + cfg().banDays * 86400000L));
            save();
        }
        service.alerts().broadcast(ChatColor.RED + "[WD] " + ChatColor.GRAY + name + ChatColor.WHITE + " was banned while offline "
                + ChatColor.GRAY + "(" + reasonCode + ") " + ChatColor.DARK_GRAY + "#" + banId);
        service.log("BAN", name + " (" + uuid + ") " + reasonCode + " #" + banId + " offline" + (persist ? "" : " (not persisted)"));
        service.forget(uuid);
    }

    public boolean unban(String name) {
        for (BanRecord r : new ArrayList<BanRecord>(bans.values())) {
            if (r.name.equalsIgnoreCase(name) || r.uuid.toString().equalsIgnoreCase(name)) {
                bans.remove(r.uuid);
                save();
                service.log("UNBAN", r.name + " #" + r.banId);
                return true;
            }
        }
        return false;
    }

    public int bansSinceBoot() { return bansSinceBoot.get(); }

    public int activeBans() { return bans.size(); }

    private Game gameOf(Player p) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return null;
        try { return gm.gameOf(p); } catch (Throwable t) { return null; }
    }

    private void thankReporters(Player banned) {
        PlayerData d = service.dataIfPresent(banned.getUniqueId());
        if (d == null) return;
        for (UUID id : d.reporters) {
            Player r = Bukkit.getPlayer(id);
            if (r == null || !r.isOnline()) continue;
            r.sendMessage(ChatColor.YELLOW.toString() + ChatColor.BOLD + "WATCHDOG " + ChatColor.RESET + ChatColor.GREEN
                    + "A player you reported has been banned. Thanks for your report!");
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        BanRecord r = bans.get(e.getUniqueId());
        if (r == null) return;
        long now = System.currentTimeMillis();
        if (!r.active(now)) {
            bans.remove(r.uuid);
            save();
            return;
        }
        if (!cfg().banHumans) return; // bans are only enforced while ban-humans is on
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, BanScreen.screen(r.expires - now, r.banId));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Pending pd = pending.get(e.getPlayer().getUniqueId());
        if (pd == null) return;
        // Cheaters who log out before the wave still get banned.
        if (pd.task != null) pd.task.cancel();
        pending.remove(pd.uuid);
        offlineBan(pd.uuid, pd.name, pd.reason, pd.bot);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameState(GameStateChangeEvent e) {
        if (e.to() != GameState.ENDING || e.game() == null) return;
        String id;
        try { id = e.game().id(); } catch (Throwable t) { return; }
        for (final Pending pd : pending.values()) {
            if (pd.gameId == null || !pd.gameId.equals(id)) continue;
            if (pd.task != null) pd.task.cancel();
            pd.task = Bukkit.getScheduler().runTaskLater(service.plugin(), () -> fire(pd), 40L + random.nextInt(80));
        }
    }

    public void shutdown() {
        for (Pending pd : pending.values()) if (pd.task != null) pd.task.cancel();
        pending.clear();
    }
}
