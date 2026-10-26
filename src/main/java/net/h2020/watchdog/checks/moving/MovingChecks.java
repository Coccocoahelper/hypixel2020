package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.WatchdogService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the watchdog-movement module (NoCheatPlus 3.16 moving + net groups for 1.8.8):
 * SurvivalFly (+hover), CreativeFly, NoFall, MorePackets, Passable, Velocity and the net checks Timer,
 * FlyingFrequency, KeepAlive, PacketOrder. Called by WatchdogModule after the WatchdogService is registered.
 * Violation levels are keyed by CheckType in the framework, so no registration is needed.
 */
public final class MovingChecks {
    private static MovingEngine engine;

    private MovingChecks() {}

    public static void registerAll(WatchdogService service, JavaPlugin plugin) {
        if (engine != null) shutdown();
        MovingEngine e = new MovingEngine(service, plugin);
        e.start();
        engine = e;
    }

    public static void shutdown() {
        MovingEngine e = engine;
        engine = null;
        if (e != null) e.stop();
    }

    /** Per-player NCP-style debug output (every move + every flag) for recording/diagnosis. */
    public static void setDebug(Player p, boolean on) {
        MovingEngine e = engine;
        if (e != null) e.setDebug(p, on);
    }
}
