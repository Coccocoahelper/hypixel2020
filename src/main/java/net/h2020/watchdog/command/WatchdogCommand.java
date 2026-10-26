package net.h2020.watchdog.command;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Util;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * /wd staff tools: alerts, vl, ban, unban, exempt, unexempt, debug, reset, announce, info.
 */
public final class WatchdogCommand implements CommandExecutor, TabCompleter {
    private static final String P = ChatColor.RED + "[WD] " + ChatColor.GRAY;
    private static final List<String> SUBS = Arrays.asList("alerts", "vl", "ban", "unban", "exempt", "unexempt", "debug",
            "reset", "announce", "info");

    private final WatchdogServiceImpl service;

    public WatchdogCommand(WatchdogServiceImpl service) { this.service = service; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission(Util.ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command!");
            return true;
        }
        if (args.length == 0) { help(sender); return true; }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "alerts": {
                if (!(sender instanceof Player)) { sender.sendMessage(P + (service.alerts().console() ? "Console receives alerts." : "Console alerts are off (watchdog.console-alerts); flags are in the Watchdog log.")); return true; }
                boolean on = service.alerts().toggle((Player) sender);
                sender.sendMessage(P + "Watchdog alerts " + (on ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.GRAY + ".");
                return true;
            }
            case "vl": {
                Player t = target(sender, args, 1);
                if (t == null) return true;
                vl(sender, t);
                return true;
            }
            case "ban": {
                Player t = target(sender, args, 1);
                if (t == null) return true;
                String reason = args.length > 2 ? args[2].toUpperCase() : "MANUAL";
                sender.sendMessage(P + "Banning " + ChatColor.WHITE + t.getName() + ChatColor.GRAY + " (" + reason + ")");
                service.ban(t, reason);
                return true;
            }
            case "unban": {
                if (args.length < 2) { sender.sendMessage(P + "Usage: /wd unban <name>"); return true; }
                sender.sendMessage(P + (service.bans().unban(args[1]) ? "Unbanned " + args[1] + "." : "No active ban for " + args[1] + "."));
                return true;
            }
            case "exempt": {
                if (args.length < 4) { sender.sendMessage(P + "Usage: /wd exempt <player> <check|group|all> <seconds>"); return true; }
                Player t = target(sender, args, 1);
                if (t == null) return true;
                long ms;
                try { ms = (long) (Double.parseDouble(args[3]) * 1000); } catch (NumberFormatException e) {
                    sender.sendMessage(P + "Invalid seconds: " + args[3]);
                    return true;
                }
                String what = args[2].toUpperCase();
                if (what.equals("ALL")) {
                    for (CheckType c : CheckType.values()) service.exempt(t, c, ms);
                } else {
                    CheckType.Group group = null;
                    for (CheckType.Group g : CheckType.Group.values()) if (g.name().equals(what)) group = g;
                    if (group != null) service.exemptGroup(t, group, ms);
                    else {
                        CheckType c = parseCheck(what);
                        if (c == null) { sender.sendMessage(P + "Unknown check: " + args[2]); return true; }
                        service.exempt(t, c, ms);
                    }
                }
                sender.sendMessage(P + "Exempted " + ChatColor.WHITE + t.getName() + ChatColor.GRAY + " from " + what.toLowerCase()
                        + " for " + args[3] + "s.");
                return true;
            }
            case "unexempt": {
                Player t = target(sender, args, 1);
                if (t == null) return true;
                service.unexempt(t);
                sender.sendMessage(P + "Removed all exemptions of " + t.getName() + ".");
                return true;
            }
            case "debug": {
                Player t = target(sender, args, 1);
                if (t == null) return true;
                if (!(sender instanceof Player)) {
                    // console: toggle the movement engine's per-move lines (mirrored to the server log)
                    boolean on = args.length < 3 || !args[2].equalsIgnoreCase("off");
                    net.h2020.watchdog.checks.moving.MovingChecks.setDebug(t, on);
                    sender.sendMessage(P + "Movement debug for " + t.getName() + (on ? " enabled" : " disabled") + " (server log).");
                    return true;
                }
                boolean on = service.toggleDebug((Player) sender, t);
                // self-debug also shows the movement engine's per-move lines (they are sent to the moving player)
                if (t == sender) net.h2020.watchdog.checks.moving.MovingChecks.setDebug(t, on);
                sender.sendMessage(P + "Live check output for " + ChatColor.WHITE + t.getName() + ChatColor.GRAY + " "
                        + (on ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.GRAY + ".");
                return true;
            }
            case "reset": {
                Player t = target(sender, args, 1);
                if (t == null) return true;
                service.forget(t.getUniqueId());
                service.bans().cancelPending(t.getUniqueId());
                sender.sendMessage(P + "Reset all violation levels (and a pending ban) of " + t.getName() + ".");
                return true;
            }
            case "announce":
                service.announcer().announce();
                return true;
            case "info":
                sender.sendMessage(P + "Bans since boot: " + ChatColor.WHITE + service.bansLast7Days() + ChatColor.GRAY
                        + "  persisted bans: " + ChatColor.WHITE + service.bans().activeBans() + ChatColor.GRAY
                        + "  ban-humans: " + ChatColor.WHITE + service.config().banHumans + ChatColor.GRAY
                        + "  exempt-admins: " + ChatColor.WHITE + service.config().exemptAdmins);
                return true;
            default:
                help(sender);
                return true;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "Watchdog commands:");
        s.sendMessage(ChatColor.YELLOW + "/wd alerts" + ChatColor.GRAY + " - toggle staff alerts");
        s.sendMessage(ChatColor.YELLOW + "/wd vl <player>" + ChatColor.GRAY + " - violation levels");
        s.sendMessage(ChatColor.YELLOW + "/wd ban <player> [reason]" + ChatColor.GRAY + " - ban now");
        s.sendMessage(ChatColor.YELLOW + "/wd unban <name>" + ChatColor.GRAY + " - lift a persisted ban");
        s.sendMessage(ChatColor.YELLOW + "/wd exempt <player> <check|group|all> <seconds>" + ChatColor.GRAY + " - exempt");
        s.sendMessage(ChatColor.YELLOW + "/wd unexempt <player>" + ChatColor.GRAY + " - clear exemptions");
        s.sendMessage(ChatColor.YELLOW + "/wd debug <player>" + ChatColor.GRAY + " - live per-check output");
        s.sendMessage(ChatColor.YELLOW + "/wd reset <player>" + ChatColor.GRAY + " - clear violation levels");
        s.sendMessage(ChatColor.YELLOW + "/wd announce" + ChatColor.GRAY + " - broadcast the announcement now");
        s.sendMessage(ChatColor.YELLOW + "/wd info" + ChatColor.GRAY + " - status");
    }

    private void vl(CommandSender s, Player t) {
        PlayerData d = service.dataIfPresent(t.getUniqueId());
        s.sendMessage(ChatColor.GOLD + "Violation levels of " + t.getName() + ChatColor.GRAY + " (ping " + Util.ping(t) + "ms"
                + (Util.isBot(t) ? ", bot" : "") + (service.bans().isPending(t.getUniqueId()) ? ChatColor.RED + ", ban pending" : "")
                + ChatColor.GRAY + ")");
        if (d == null) { s.sendMessage(ChatColor.GRAY + "  no data"); return; }
        int shown = 0;
        CheckType[] checks = d.checksWithData();
        Arrays.sort(checks);
        for (CheckType c : checks) {
            double vl = service.violationLevel(t, c);
            if (vl < 0.05) continue;
            double ban = service.config().settings(c).actions.banThreshold();
            ChatColor col = vl >= ban ? ChatColor.DARK_RED : vl >= ban / 2 ? ChatColor.RED : ChatColor.YELLOW;
            s.sendMessage(ChatColor.GRAY + "  " + c.name() + ": " + col + Util.fmt1(vl)
                    + (ban < Double.MAX_VALUE ? ChatColor.DARK_GRAY + " / " + Util.fmt1(ban) : ""));
            shown++;
        }
        for (Map.Entry<CheckType, Long> e : d.exemptions().entrySet()) {
            long left = e.getValue() - System.currentTimeMillis();
            if (left > 0) s.sendMessage(ChatColor.DARK_GRAY + "  exempt " + e.getKey().name() + " " + (left / 1000) + "s");
        }
        if (shown == 0) s.sendMessage(ChatColor.GREEN + "  clean");
    }

    private Player target(CommandSender s, String[] args, int idx) {
        if (args.length <= idx) {
            s.sendMessage(P + "Missing player name.");
            return null;
        }
        Player t = ReportCommand.find(args[idx]);
        if (t == null) s.sendMessage(ChatColor.RED + "Can't find a player by the name of '" + args[idx] + "'");
        return t;
    }

    private static CheckType parseCheck(String s) {
        for (CheckType c : CheckType.values()) {
            if (c.name().equals(s) || c.name().endsWith("_" + s)) return c;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("alerts") && !args[0].equalsIgnoreCase("announce")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("exempt")) {
            List<String> opts = new ArrayList<String>();
            opts.add("all");
            for (CheckType.Group g : CheckType.Group.values()) opts.add(g.name().toLowerCase());
            for (CheckType c : CheckType.values()) opts.add(c.name().toLowerCase());
            for (String o : opts) if (o.startsWith(args[2].toLowerCase())) out.add(o);
        }
        return out;
    }
}
