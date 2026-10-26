package net.h2020.core.profile;

import net.h2020.api.core.ProfileService;
import net.h2020.api.core.Rank;
import net.h2020.core.CoreConfig;
import net.h2020.core.CoreUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Ranks / plus colours / network levels / lobby stats. Humans persisted in plugins/Hypixel2020/profiles.yml. */
public final class ProfileServiceImpl implements ProfileService, Listener {
    /** Called (main thread) after a player's rank or level changed so nametags/boards can refresh. */
    public interface ChangeListener { void profileChanged(Player p); }

    private final JavaPlugin plugin;
    private final CoreConfig config;
    private final File file;
    private final YamlConfiguration yml;
    private final Map<UUID, ProfileData> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> botFlag = new ConcurrentHashMap<>();
    private volatile ChangeListener changeListener;

    public ProfileServiceImpl(JavaPlugin plugin, CoreConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "profiles.yml");
        this.yml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    public void setChangeListener(ChangeListener l) { this.changeListener = l; }

    /** The (possibly freshly created) profile of an online player. Thread-safe. */
    public ProfileData data(Player p) {
        ProfileData d = profiles.get(p.getUniqueId());
        if (d != null) return d;
        // after PlayerQuitEvent (late scheduled tasks, async chat) never re-cache: it would leak and go stale
        if (!p.isOnline()) return create(p);
        synchronized (this) {
            d = profiles.get(p.getUniqueId());
            if (d != null) return d;
            d = create(p);
            botFlag.put(p.getUniqueId(), CoreUtil.isBot(p));
            profiles.put(p.getUniqueId(), d);
            return d;
        }
    }

    private ProfileData create(Player p) {
        ProfileData d = new ProfileData(p.getUniqueId(), p.getName());
        boolean bot = CoreUtil.isBot(p);
        d.lobbyNumber = config.lobbyNumber;
        if (bot) {
            d.rank = Rank.NONE;
            d.networkLevel = 1 + (Math.abs(p.getUniqueId().hashCode()) % 40);
            d.randomizeStats(p.getUniqueId().getMostSignificantBits());
            return d;
        }
        ConfigurationSection s = yml.getConfigurationSection(p.getUniqueId().toString());
        if (s != null) {
            d.load(s);
        } else {
            d.rank = config.defaultRank;
            d.plusColor = config.defaultPlusColor;
            d.networkLevel = Math.max(1, config.defaultLevel);
            d.randomizeStats(p.getUniqueId().getLeastSignificantBits());
            d.lobbyNumber = config.lobbyNumber;
        }
        d.name = p.getName();
        return d;
    }

    public boolean isBotProfile(Player p) {
        Boolean b = botFlag.get(p.getUniqueId());
        return b != null ? b : CoreUtil.isBot(p);
    }

    @Override public Rank rank(Player p) { return data(p).rank; }

    @Override public ChatColor plusColor(Player p) { return data(p).plusColor; }

    @Override
    public void setRank(Player p, Rank rank, ChatColor plusColor) {
        ProfileData d = data(p);
        d.rank = rank == null ? Rank.NONE : rank;
        if (plusColor != null && plusColor.isColor()) d.plusColor = plusColor;
        else if (d.rank == Rank.MVP_PLUS || d.rank == Rank.MVP_PLUS_PLUS) d.plusColor = ChatColor.RED;
        fireChange(p);
    }

    @Override public int networkLevel(Player p) { return data(p).networkLevel; }

    @Override
    public void setNetworkLevel(Player p, int level) {
        ProfileData d = data(p);
        d.networkLevel = Math.max(1, level);
        if (isBotProfile(p)) d.randomizeStats(p.getUniqueId().getMostSignificantBits() ^ level);
        fireChange(p);
    }

    @Override
    public String displayName(Player p) {
        ProfileData d = data(p);
        return d.rank.prefix(d.plusColor) + p.getName();
    }

    /** Name tag prefix (rank prefix, max 16 chars) for lobbies. */
    public String nametagPrefix(Player p) {
        ProfileData d = data(p);
        String pre = d.rank.prefix(d.plusColor);
        return pre.length() > 16 ? pre.substring(0, 16) : pre;
    }

    private void fireChange(final Player p) {
        final ChangeListener l = changeListener;
        if (l == null) return;
        if (Bukkit.isPrimaryThread()) l.profileChanged(p);
        else Bukkit.getScheduler().runTask(plugin, new Runnable() { @Override public void run() { l.profileChanged(p); } });
    }

    // ---- persistence ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ProfileData d = data(p);
        d.name = p.getName();
        if (config.opLocalPlayers && !p.isOp() && !isBotProfile(p) && p.getAddress() != null
                && p.getAddress().getAddress().isLoopbackAddress()) {
            p.setOp(true);
            plugin.getLogger().info("Made " + p.getName() + " op (joined from localhost; core.op-local-players).");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ProfileData d = profiles.remove(p.getUniqueId());
        Boolean bot = botFlag.remove(p.getUniqueId());
        if (d != null && (bot == null || !bot)) {
            store(d);
            saveFile();
        }
    }

    private void store(ProfileData d) {
        ConfigurationSection s = yml.getConfigurationSection(d.uuid.toString());
        if (s == null) s = yml.createSection(d.uuid.toString());
        d.save(s);
    }

    /** Flush all online humans to disk. */
    public void saveAll() {
        for (ProfileData d : profiles.values()) {
            Boolean bot = botFlag.get(d.uuid);
            if (bot == null || !bot) store(d);
        }
        saveFile();
    }

    private void saveFile() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save profiles.yml", ex);
        }
    }
}
