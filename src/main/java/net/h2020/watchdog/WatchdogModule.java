package net.h2020.watchdog;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.packet.PacketHub;
import net.h2020.api.watchdog.WatchdogService;
import net.h2020.watchdog.checks.blockbreak.BlockBreakChecks;
import net.h2020.watchdog.checks.blockplace.BlockPlaceChecks;
import net.h2020.watchdog.checks.fight.FightChecks;
import net.h2020.watchdog.checks.inventory.InventoryChecks;
import net.h2020.watchdog.checks.moving.MovingChecks;
import net.h2020.watchdog.command.ReportCommand;
import net.h2020.watchdog.command.WatchdogCommand;
import net.h2020.watchdog.framework.PacketRouter;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Watchdog: NoCheatPlus-3.16-style anticheat with Hypixel 2020 presentation (delayed bans, announcements, reports).
 * Builds the framework, registers the WatchdogService, the combat/block/inventory checks of this module and finally
 * the movement checks of the movement builder.
 */
public final class WatchdogModule implements Module {
    private WatchdogServiceImpl service;
    private PacketRouter router;

    @Override public String name() { return "watchdog"; }

    @Override
    public void enable(JavaPlugin plugin) {
        WatchdogConfig config = WatchdogConfig.load(plugin);
        service = new WatchdogServiceImpl(plugin, config);
        service.start();
        H2020.register(WatchdogService.class, service);

        router = new PacketRouter(service);
        // Order matters: block place/break consume their arm swings before fight sees them.
        BlockPlaceChecks.register(service, router, plugin);
        BlockBreakChecks.register(service, router, plugin);
        FightChecks.register(service, router, plugin);
        InventoryChecks.register(service, router, plugin);
        PacketHub.register(router);

        WatchdogCommand wd = new WatchdogCommand(service);
        PluginCommand c = plugin.getCommand("wd");
        if (c != null) { c.setExecutor(wd); c.setTabCompleter(wd); }
        ReportCommand rc = new ReportCommand(service);
        PluginCommand r = plugin.getCommand("report");
        if (r != null) { r.setExecutor(rc); r.setTabCompleter(rc); }

        MovingChecks.registerAll(service, plugin);
    }

    @Override
    public void disable() {
        try { MovingChecks.shutdown(); } catch (Throwable ignored) {}
        if (router != null) PacketHub.unregister(router);
        if (service != null) service.shutdown(); // unregisters only this module's listeners/tasks
    }
}
