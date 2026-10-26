package net.h2020;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.bedwars.BedwarsModule;
import net.h2020.bots.BotsModule;
import net.h2020.core.CoreModule;
import net.h2020.maps.MapsModule;
import net.h2020.skywars.SkywarsModule;
import net.h2020.watchdog.WatchdogModule;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public final class H2020Plugin extends JavaPlugin {
    private final List<Module> enabled = new ArrayList<>();

    @Override
    public void onEnable() {
        H2020.init(this);
        saveDefaultConfig();
        List<Module> order = Arrays.asList(
                new CoreModule(), new MapsModule(), new WatchdogModule(),
                new BotsModule(), new BedwarsModule(), new SkywarsModule());
        for (Module m : order) {
            try {
                m.enable(this);
                enabled.add(m);
                getLogger().info("Enabled module " + m.name());
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Failed to enable module " + m.name(), t);
            }
        }
    }

    @Override
    public void onDisable() {
        List<Module> rev = new ArrayList<>(enabled);
        Collections.reverse(rev);
        for (Module m : rev) {
            try { m.disable(); } catch (Throwable t) { getLogger().log(Level.SEVERE, "Failed to disable " + m.name(), t); }
        }
        enabled.clear();
    }
}
