package net.h2020.watchdog.command;

import net.h2020.watchdog.WatchdogServiceImpl;
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

/** /report <player> [reason] (aliases /wdr, /watchdogreport). */
public final class ReportCommand implements CommandExecutor, TabCompleter {
    private static final List<String> REASONS = Arrays.asList("killaura", "reach", "fly", "speed", "autoclicker",
            "scaffold", "fastplace", "antiknockback", "cheating", "chat");

    private final WatchdogServiceImpl service;

    public ReportCommand(WatchdogServiceImpl service) { this.service = service; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can report.");
            return true;
        }
        Player p = (Player) sender;
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Invalid usage! Correct usage: /" + label.toLowerCase() + " <player> [reason]");
            return true;
        }
        Player target = find(args[0]);
        if (target == null) {
            p.sendMessage(ChatColor.RED + "Can't find a player by the name of '" + args[0] + "'");
            return true;
        }
        StringBuilder reason = new StringBuilder();
        for (int i = 1; i < args.length; i++) { if (i > 1) reason.append(' '); reason.append(args[i]); }
        service.reports().report(p, target, reason.toString().trim());
        return true;
    }

    static Player find(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(name)) return p;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        } else if (args.length == 2) {
            for (String r : REASONS) if (r.startsWith(args[1].toLowerCase())) out.add(r);
        }
        return out;
    }
}
