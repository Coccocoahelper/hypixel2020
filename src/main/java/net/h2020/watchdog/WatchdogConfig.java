package net.h2020.watchdog;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.framework.ActionList;
import net.h2020.watchdog.framework.CheckSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Map;

/**
 * Watchdog settings. Defaults live in code (core owns config.yml); optional overrides are read from the
 * {@code watchdog:} section, e.g.
 * <pre>
 * watchdog:
 *   ban-humans: false
 *   exempt-admins: true
 *   checks:
 *     fight_reach: { enabled: true, actions: "cancel vl>5 cancel log alert vl>35 cancel log alert ban", decay: 0.98 }
 * </pre>
 */
public final class WatchdogConfig {
    public boolean banHumans = false;
    public boolean exemptAdmins = true;
    /**
     * watchdog.admin-reach-limit: reach limit (blocks) for ops / admins, so the owner's client can film long-reach hits
     * while bots and other players stay on the vanilla 3.0. 0 = no special limit (default).
     */
    public double adminReachLimit = 0.0;
    /**
     * watchdog.movement.legacy-2020-crits-gap: Hypixel's 2020 Watchdog let "packet criticals" through - 2-3 position
     * packets on the spot (a rise of <= 0.0625 then a drop, onGround=false) sent right before the attack. When on,
     * FIGHT_CRITICAL accepts exactly that burst (net.h2020.watchdog.framework.LegacyCritBurst); the moving/net side
     * reads the same key (MovingConfig). Off by default.
     */
    public boolean legacyCritsGap = false;
    public int banDelayMinSeconds = 5;
    public int banDelayMaxSeconds = 40;
    /** Chance that a ban of a player in a running game is held back until that game ends. */
    public double endOfGameBanChance = 0.3;
    /** Upper bound for an end-of-game ban wait. */
    public int endOfGameMaxWaitSeconds = 420;
    public int banDays = 30;
    public int announceMinMinutes = 10;
    public int announceMaxMinutes = 15;
    /** Flag alerts in console. Off by default: every flag is still written to the Watchdog log file. */
    public boolean consoleAlerts = false;
    /** In-game [WD] flag alerts for admins start switched on. Off by default; /wd alerts turns them on per player. */
    public boolean staffAlerts = false;
    /** Violation multiplier for players reported recently ("boosting priority"). */
    public double reportBoost = 1.5;
    public int reportBoostMinutes = 10;
    public int reportCooldownSeconds = 60;

    private final Map<CheckType, CheckSettings> checks = new EnumMap<CheckType, CheckSettings>(CheckType.class);

    public CheckSettings settings(CheckType type) {
        CheckSettings s = checks.get(type);
        return s != null ? s : DEFAULT_FALLBACK;
    }

    private static final CheckSettings DEFAULT_FALLBACK =
            CheckSettings.of("vl>5 log:0:5 alert:0:5 vl>60 log alert ban", 0.97, 0.05);

    /** Code defaults: action strings modelled on NCP's default config, ban tiers added for the Watchdog flow. */
    private static Map<CheckType, CheckSettings> defaults() {
        Map<CheckType, CheckSettings> m = new EnumMap<CheckType, CheckSettings>(CheckType.class);
        // ---- moving / net (thresholds are generous; the movement builder decides setbacks itself) ----
        // SurvivalFly adds excess*100 per move (NCP scale): one desync can reach 50, a speed/fly cheat adds hundreds
        // per second. The ban tier sits well above what a couple of lag-induced desyncs can reach.
        m.put(CheckType.MOVING_SURVIVALFLY, CheckSettings.of("cancel vl>10 cancel log:3:3 alert:3:3 vl>400 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.MOVING_CREATIVEFLY, CheckSettings.of("cancel vl>10 cancel log:3:3 alert:3:3 vl>400 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.MOVING_NOFALL, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.MOVING_MOREPACKETS, CheckSettings.of("cancel vl>10 cancel log:0:5 alert:0:5 vl>80 cancel log alert ban", 0.95, 0.2));
        m.put(CheckType.MOVING_PASSABLE, CheckSettings.of("cancel vl>10 cancel log:0:5 alert:0:5 vl>60 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.MOVING_VELOCITY, CheckSettings.of("vl>5 log:0:3 alert:0:3 vl>40 log alert ban", 0.98, 0.05));
        m.put(CheckType.NET_TIMER, CheckSettings.of("cancel vl>10 cancel log:0:5 alert:0:5 vl>80 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.NET_FLYINGFREQUENCY, CheckSettings.of("cancel vl>10 cancel log:0:5 alert:0:5 vl>60 cancel log alert ban", 0.95, 0.2));
        m.put(CheckType.NET_KEEPALIVE, CheckSettings.of("cancel vl>10 cancel log:0:5 alert:0:5 vl>50 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.NET_PACKETORDER, CheckSettings.of("cancel vl>5 cancel log:0:5 alert:0:5 vl>40 cancel log alert ban", 0.97, 0.1));

        // ---- fight ----
        m.put(CheckType.FIGHT_REACH, CheckSettings.of("cancel vl>5 cancel log:0:2 alert:0:2 vl>35 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.FIGHT_SPEED, CheckSettings.of("cancel vl>5 cancel log:0:2 alert:0:2 vl>40 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.FIGHT_ANGLE, CheckSettings.of("cancel vl>50 cancel log:0:3 alert:0:3 vl>250 cancel log alert ban", 0.98, 0.5));
        m.put(CheckType.FIGHT_DIRECTION, CheckSettings.of("cancel vl>5 cancel log:0:2 alert:0:2 vl>30 cancel log alert ban", 0.97, 0.05));
        m.put(CheckType.FIGHT_NOSWING, CheckSettings.of("cancel vl>3 cancel log:0:3 alert:0:3 vl>20 cancel log alert ban", 0.97, 0.05));
        m.put(CheckType.FIGHT_CRITICAL, CheckSettings.of("cancel vl>4 cancel log:0:3 alert:0:3 vl>25 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.FIGHT_SELFHIT, CheckSettings.of("cancel vl>1 cancel log:0:3 alert:0:3 vl>10 cancel log alert ban", 0.99, 0.02));
        m.put(CheckType.FIGHT_GODMODE, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.FIGHT_FASTHEAL, CheckSettings.of("cancel vl>3 cancel log:0:5 alert:0:5 vl>25 cancel log alert ban", 0.99, 0.02));
        m.put(CheckType.FIGHT_AIM, CheckSettings.of("vl>6 log:0:5 alert:0:5 vl>30 log alert ban", 0.995, 0.01));
        m.put(CheckType.FIGHT_CLICKPATTERN, CheckSettings.of("vl>6 log:0:5 alert:0:5 vl>30 log alert ban", 0.995, 0.01));

        // ---- block place ----
        m.put(CheckType.BLOCKPLACE_FASTPLACE, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.BLOCKPLACE_REACH, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.BLOCKPLACE_DIRECTION, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban", 0.97, 0.05));
        m.put(CheckType.BLOCKPLACE_AGAINST, CheckSettings.of("vl>2 cancel vl>4 cancel log:0:3 alert:0:3 vl>30 cancel log alert ban", 0.96, 0.05));
        m.put(CheckType.BLOCKPLACE_SCAFFOLD, CheckSettings.of("vl>4 cancel vl>8 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.BLOCKPLACE_NOSWING, CheckSettings.of("vl>3 log:0:5 alert:0:5 vl>25 log alert ban", 0.97, 0.05));

        // ---- block break ----
        m.put(CheckType.BLOCKBREAK_FASTBREAK, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.BLOCKBREAK_REACH, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.BLOCKBREAK_DIRECTION, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>35 cancel log alert ban", 0.97, 0.05));
        m.put(CheckType.BLOCKBREAK_NOSWING, CheckSettings.of("cancel vl>3 cancel log:0:5 alert:0:5 vl>25 cancel log alert ban", 0.97, 0.05));

        // ---- inventory ----
        m.put(CheckType.INVENTORY_FASTCLICK, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.INVENTORY_INSTANTBOW, CheckSettings.of("cancel vl>3 cancel log:0:3 alert:0:3 vl>25 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.INVENTORY_INSTANTEAT, CheckSettings.of("cancel vl>3 cancel log:0:3 alert:0:3 vl>20 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.INVENTORY_FASTCONSUME, CheckSettings.of("cancel vl>3 cancel log:0:3 alert:0:3 vl>25 cancel log alert ban", 0.98, 0.05));
        m.put(CheckType.INVENTORY_OPEN, CheckSettings.of("cancel vl>5 cancel log:0:3 alert:0:3 vl>40 cancel log alert ban", 0.97, 0.1));
        m.put(CheckType.INVENTORY_ITEMS, CheckSettings.of("cancel vl>0.5 cancel log:0:2 alert:0:2 vl>8 cancel log alert ban", 0.995, 0.01));
        return m;
    }

    public static WatchdogConfig load(JavaPlugin plugin) {
        WatchdogConfig c = new WatchdogConfig();
        c.checks.putAll(defaults());
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("watchdog");
        if (s == null) return c;
        c.banHumans = s.getBoolean("ban-humans", c.banHumans);
        c.exemptAdmins = s.getBoolean("exempt-admins", c.exemptAdmins);
        c.adminReachLimit = Math.max(0, s.getDouble("admin-reach-limit", c.adminReachLimit));
        ConfigurationSection mv = s.getConfigurationSection("movement");
        if (mv != null) c.legacyCritsGap = mv.getBoolean("legacy-2020-crits-gap", c.legacyCritsGap);
        c.banDelayMinSeconds = Math.max(0, s.getInt("ban-delay-min", c.banDelayMinSeconds));
        c.banDelayMaxSeconds = Math.max(c.banDelayMinSeconds, s.getInt("ban-delay-max", c.banDelayMaxSeconds));
        c.endOfGameBanChance = s.getDouble("end-of-game-ban-chance", c.endOfGameBanChance);
        c.banDays = Math.max(1, s.getInt("ban-days", c.banDays));
        c.announceMinMinutes = Math.max(1, s.getInt("announce-min-minutes", c.announceMinMinutes));
        c.announceMaxMinutes = Math.max(c.announceMinMinutes, s.getInt("announce-max-minutes", c.announceMaxMinutes));
        c.consoleAlerts = s.getBoolean("console-alerts", c.consoleAlerts);
        c.staffAlerts = s.getBoolean("staff-alerts", c.staffAlerts);
        c.reportBoost = s.getDouble("report-boost", c.reportBoost);
        c.reportBoostMinutes = s.getInt("report-boost-minutes", c.reportBoostMinutes);
        ConfigurationSection cs = s.getConfigurationSection("checks");
        if (cs != null) {
            for (CheckType t : CheckType.values()) {
                ConfigurationSection one = cs.getConfigurationSection(t.name().toLowerCase());
                if (one == null) continue;
                CheckSettings def = c.settings(t);
                boolean en = one.getBoolean("enabled", def.enabled);
                ActionList al = one.isString("actions") ? ActionList.parse(one.getString("actions")) : def.actions;
                double decay = one.getDouble("decay", def.decayFactor);
                double lin = one.getDouble("decay-linear", def.decayLinear);
                c.checks.put(t, new CheckSettings(en, al, decay, lin));
            }
        }
        return c;
    }
}
