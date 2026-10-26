package net.h2020.watchdog.framework;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import org.bukkit.entity.Player;

/** Base class of a single check (NCP: one Check subclass per check type). */
public abstract class Check {
    protected final WatchdogServiceImpl service;
    protected final CheckType type;

    protected Check(WatchdogServiceImpl service, CheckType type) {
        this.service = service;
        this.type = type;
    }

    public CheckType type() { return type; }

    /** Adds VL through the framework; returns the cancel decision. */
    protected boolean flag(Player p, double vl, String details) { return service.flag(p, type, vl, details); }

    protected boolean exempt(Player p) { return service.isExempt(p, type); }

    protected void reward(Player p, double factor) { service.reward(p, type, factor); }

    protected boolean debugging(Player p) { return service.debugging(p); }

    protected void debug(Player p, String msg) { service.debug(p, type, msg); }
}
