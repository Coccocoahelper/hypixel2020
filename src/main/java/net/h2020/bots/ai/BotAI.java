package net.h2020.bots.ai;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.BedwarsView;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.SkywarsView;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.brain.GameBrain;
import net.h2020.bots.ai.brain.LobbyBrain;
import net.h2020.bots.ai.brain.SkywarsBrain;
import net.h2020.bots.ai.nav.PathService;
import net.h2020.bots.ai.perception.SoundBus;
import net.h2020.bots.ai.world.Raycast;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Entry point of the bot AI (decision layer). Called once by BotsModule.enable() after BotService is registered.
 * Assigns a brain to every bot according to where it is (lobby, Bed Wars game, SkyWars game), runs the global
 * per-tick schedulers (path search budget, raycast budget, sound bus), and dispatches game events to brains.
 */
public final class BotAI {
    private static JavaPlugin plugin;
    private static BukkitTask task;
    private static AIListener listener;
    private static final Map<UUID, Persona> PERSONAS = new ConcurrentHashMap<>();
    private static final Map<UUID, BaseBrain> BRAINS = new ConcurrentHashMap<>();
    private static long ticks;

    private BotAI() {}

    public static void install(JavaPlugin p) {
        plugin = p;
        FightStats.install(p);
        net.h2020.bots.ai.combat.CombatStats.install(p);
        ConfigurationSection cfg = p.getConfig() == null ? null : p.getConfig().getConfigurationSection("bots-ai");
        if (cfg != null) {
            PathService.setBudgetMicros(cfg.getLong("path-budget-micros", 4000));
            Raycast.setPerTick(cfg.getInt("raycasts-per-tick", 240));
        }
        listener = new AIListener();
        Bukkit.getPluginManager().registerEvents(listener, p);
        task = Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tickAll(); }
        }, 1L, 1L);
    }

    public static void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        if (listener != null) { HandlerList.unregisterAll(listener); listener = null; }
        BotService svc = H2020.find(BotService.class);
        if (svc != null) {
            for (Bot b : new ArrayList<>(svc.bots())) {
                try { if (b.brain() instanceof BaseBrain) b.setBrain(null); } catch (Throwable ignored) {}
            }
        }
        PathService.clear();
        SoundBus.clear();
        BRAINS.clear();
    }

    public static Logger log() { return plugin != null ? plugin.getLogger() : Logger.getLogger("H2020"); }

    public static JavaPlugin plugin() { return plugin; }

    private static void tickAll() {
        ticks++;
        Raycast.resetBudget();
        SoundBus.tick();
        try { PathService.tick(); } catch (Throwable t) { log().warning("[bots-ai] path service: " + t); }
        if (ticks % 20 == 0) reconcileAll();
    }

    /** Stable personality for a bot. */
    public static Persona persona(Bot bot) {
        UUID id = bot.profile().uuid;
        Persona p = PERSONAS.get(id);
        if (p == null) { p = new Persona(bot.profile()); PERSONAS.put(id, p); }
        return p;
    }

    /** Bots get a fresh UUID per spawn; drop the persona when the bot leaves so the map does not grow forever. */
    static void forgetPersona(UUID id) { PERSONAS.remove(id); }

    public static void register(Player p, BaseBrain b) { BRAINS.put(p.getUniqueId(), b); }

    public static void unregister(Player p, BaseBrain b) { BRAINS.remove(p.getUniqueId(), b); }

    public static BaseBrain brainOf(Player p) { return p == null ? null : BRAINS.get(p.getUniqueId()); }

    public static Collection<BaseBrain> brains() { return BRAINS.values(); }

    // ------------------------------------------------------------------------------------------- brain assignment

    static void reconcileAll() {
        BotService svc = H2020.find(BotService.class);
        if (svc == null) return;
        for (Bot b : new ArrayList<>(svc.bots())) {
            try { reconcile(b); } catch (Throwable t) { log().warning("[bots-ai] reconcile " + t); }
        }
    }

    static void reconcile(Player p) {
        BotService svc = H2020.find(BotService.class);
        if (svc == null || p == null) return;
        Bot b = svc.bot(p);
        if (b != null) reconcile(b);
    }

    static void reconcile(Bot b) {
        if (b.isRemoved() || b.player() == null || !b.player().isOnline()) return;
        BotBrain cur = b.brain();
        if (cur != null && !(cur instanceof BaseBrain)) return; // someone else (e.g. a command) drives this bot
        GameManager gm = H2020.find(GameManager.class);
        Game g = gm == null ? null : gm.gameOf(b.player());
        if (g != null) {
            if (cur instanceof GameBrain && ((GameBrain) cur).game() == g) return;
            BotBrain nb = null;
            if (g.type().family == GameFamily.BEDWARS && g instanceof BedwarsView) nb = new BedwarsBrain((BedwarsView) g);
            else if (g.type().family == GameFamily.SKYWARS && g instanceof SkywarsView) nb = new SkywarsBrain((SkywarsView) g);
            if (nb != null) { b.setBrain(nb); return; }
            if (cur instanceof LobbyBrain) return;
            b.setBrain(new LobbyBrain());
            return;
        }
        if (cur instanceof LobbyBrain) return;
        b.setBrain(new LobbyBrain());
    }

    /** Reconcile on the next tick (after the game finished moving the player). */
    static void reconcileSoon(final Player p) {
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { reconcile(p); }
        }, 2L);
    }

    static void reconcileGame(final Game g) {
        if (plugin == null) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                List<Player> ps = new ArrayList<>(g.players());
                for (Player p : ps) reconcile(p);
            }
        });
    }
}
