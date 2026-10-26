package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Which moving/net checks run. Defaults in code; optional overrides from config.yml:
 * <pre>
 * watchdog:
 *   movement:
 *     moving_survivalfly: true
 *     net_timer: false
 * </pre>
 */
final class MovingConfig {
    private final EnumSet<CheckType> enabled = EnumSet.noneOf(CheckType.class);
    /**
     * watchdog.movement.legacy-2020-hover-gap: reproduce the blind spot Hypixel's 2020 Watchdog had for the
     * "1/64 hover" fly (Y alternating +-1/16 per packet around a fixed height, ~2.15x sprint speed). Off by default.
     */
    boolean legacyHoverGap;
    /**
     * watchdog.movement.legacy-2020-bhop-gap: accept NCP-style bunnyhops like Hypixel's 2020 Watchdog did - a jump
     * tick up to 2.15x sprint speed, then air ticks losing only 1/160 of the last distance. Off by default.
     */
    boolean legacyBhopGap;
    /**
     * watchdog.movement.legacy-2020-velocity-gap: Hypixel's 2020 Watchdog had no working anti-knockback check, so
     * "0/0" velocity (ignoring S12 knockback) went unpunished. When on, MOVING_VELOCITY never judges a hit. Off by default.
     */
    boolean legacyVelocityGap;
    /**
     * watchdog.movement.legacy-2020-nofall-gap: Hypixel's 2020 Watchdog let the classic "Hypixel" NoFall through - a
     * single onGround=true on the normal movement packet each time ~2.5-3 blocks have been fallen, which makes vanilla
     * reset the fall distance before it ever reaches the damage threshold. When on, MOVING_NOFALL accepts exactly such
     * a reset (see NoFall.legacyReset); constant onGround, a spoof held until landing and denied landings stay caught.
     * Off by default.
     */
    boolean legacyNoFallGap;

    MovingConfig() {
        for (CheckType t : CheckType.values()) {
            if (t.group == CheckType.Group.MOVING || t.group == CheckType.Group.NET) enabled.add(t);
        }
    }

    static MovingConfig load(JavaPlugin plugin) {
        MovingConfig c = new MovingConfig();
        try {
            ConfigurationSection wd = plugin.getConfig().getConfigurationSection("watchdog");
            ConfigurationSection m = wd == null ? null : wd.getConfigurationSection("movement");
            if (m != null) {
                c.legacyHoverGap = m.getBoolean("legacy-2020-hover-gap", false);
                c.legacyBhopGap = m.getBoolean("legacy-2020-bhop-gap", false);
                c.legacyVelocityGap = m.getBoolean("legacy-2020-velocity-gap", false);
                c.legacyNoFallGap = m.getBoolean("legacy-2020-nofall-gap", false);
                for (CheckType t : CheckType.values()) {
                    if (t.group != CheckType.Group.MOVING && t.group != CheckType.Group.NET) continue;
                    String key = t.name().toLowerCase(Locale.ROOT);
                    if (m.isBoolean(key)) {
                        if (m.getBoolean(key)) c.enabled.add(t); else c.enabled.remove(t);
                    }
                }
            }
        } catch (Throwable ignored) {
            // keep defaults
        }
        return c;
    }

    boolean enabled(CheckType t) { return enabled.contains(t); }
}
