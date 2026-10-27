package net.h2020.bots;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.CheatProfile;
import net.h2020.bots.client.VirtualClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** /bot - spawn and inspect bots (video tooling). */
final class BotCommand implements CommandExecutor, TabCompleter {
    private static final String P = ChatColor.GOLD + "[Bots] " + ChatColor.RESET;
    private static final List<String> SUBS = Arrays.asList("spawn", "remove", "list", "cheat", "look", "tp", "goto",
            "say", "walk", "stop", "jump", "sneak", "aim", "attack", "aimpos", "left", "right", "slot", "click", "close", "open");

    private final JavaPlugin plugin;
    private final BotManager bots;
    private final Random rnd = new Random();

    BotCommand(JavaPlugin plugin, BotManager bots) {
        this.plugin = plugin;
        this.bots = bots;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender, label); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "spawn": spawn(sender, args); break;
                case "remove": remove(sender, args); break;
                case "list": list(sender); break;
                case "cheat": cheat(sender, args); break;
                case "look": look(sender, args); break;
                case "navstats":
                    if (args.length > 1 && args[1].equalsIgnoreCase("reset")) { net.h2020.bots.ai.nav.NavStats.reset(); sender.sendMessage(P + "navstats reset"); }
                    for (String l : net.h2020.bots.ai.nav.NavStats.report()) sender.sendMessage(l);
                    break;
                case "swstats":
                    if (args.length > 1 && args[1].equalsIgnoreCase("reset")) { net.h2020.bots.ai.brain.SwStats.reset(); sender.sendMessage(P + "swstats reset"); }
                    if (args.length > 1 && args[1].equalsIgnoreCase("debug")) {
                        net.h2020.bots.ai.brain.SwStats.debug = args.length > 2 ? args[2].equalsIgnoreCase("on") : !net.h2020.bots.ai.brain.SwStats.debug;
                        sender.sendMessage(P + "swstats debug " + (net.h2020.bots.ai.brain.SwStats.debug ? "on" : "off"));
                    }
                    for (String l : net.h2020.bots.ai.brain.SwStats.report()) sender.sendMessage(l);
                    break;
                case "pvp": {
                    // 2020 fight behaviour switch + telemetry (net.h2020.bots.ai.combat)
                    String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "stats";
                    if (a.equals("v1") || a.equals("v2") || a.equals("ab")) { net.h2020.bots.ai.combat.CombatStats.setMode(a); net.h2020.bots.ai.combat.CombatStats.save(); }
                    else if (a.equals("reset")) net.h2020.bots.ai.combat.CombatStats.reset();
                    else if (a.equals("debug")) net.h2020.bots.ai.combat.CombatStats.debug = !net.h2020.bots.ai.combat.CombatStats.debug;
                    for (String l : net.h2020.bots.ai.combat.CombatStats.report()) sender.sendMessage(l);
                    break;
                }
                case "navtrace":
                    net.h2020.bots.ai.nav.NavStats.trace = args.length > 1 && !args[1].equalsIgnoreCase("off") ? args[1] : null;
                    sender.sendMessage(P + "navtrace " + net.h2020.bots.ai.nav.NavStats.trace);
                    break;
                case "navfix":
                    if (args.length > 1) {
                        try { net.h2020.bots.ai.nav.NavStats.fix = Integer.decode(args[1]); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("navfix <mask>: 1 start, 2 headroom, 4 boxes, 8 stairs, 16 gui, 32 lookahead, 64 water"); }
                    }
                    sender.sendMessage(P + "navfix mask " + net.h2020.bots.ai.nav.NavStats.fix);
                    break;
                case "navdebug":
                    net.h2020.bots.ai.nav.NavStats.debug = args.length > 1 ? args[1].equalsIgnoreCase("on") : !net.h2020.bots.ai.nav.NavStats.debug;
                    sender.sendMessage(P + "navdebug " + (net.h2020.bots.ai.nav.NavStats.debug ? "on" : "off"));
                    break;
                case "tp": tp(sender, args); break;
                case "goto": goTo(sender, args); break;
                case "say": say(sender, args); break;
                case "walk": walk(sender, args); break;
                case "stop": stop(sender, args); break;
                case "jump": toggle(sender, args, "jump"); break;
                case "sneak": toggle(sender, args, "sneak"); break;
                case "aim": aim(sender, args, false); break;
                case "attack": aim(sender, args, true); break;
                case "aimpos": aimPos(sender, args); break;
                case "left": left(sender, args); break;
                case "right": right(sender, args); break;
                case "slot": slot(sender, args); break;
                case "click": {
                    if (args.length < 5) throw new IllegalArgumentException("Usage: /bot click <name> <rawSlot> <button> <mode>");
                    try {
                        need(args[1]).controller().clickWindow(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    } catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
                    break;
                }
                case "close":
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /bot close <name>");
                    need(args[1]).controller().closeWindow();
                    break;
                case "open": {
                    if (args.length < 5) throw new IllegalArgumentException("Usage: /bot open <name> <x> <y> <z>");
                    FakeBot b = need(args[1]);
                    try {
                        b.controller().openContainer(b.player().getWorld().getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])));
                    } catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
                    break;
                }
                default: help(sender, label);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(P + ChatColor.RED + e.getMessage());
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " spawn [name|#count] [cheat]" + ChatColor.GRAY + " - spawn at you");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " remove <name|all>" + ChatColor.GRAY + " - disconnect bot(s)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " list" + ChatColor.GRAY + " - online bots");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " cheat <name|all> <" + cheatNames() + ">");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " look <name>" + ChatColor.GRAY + " - aim/physics debug");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " navstats [reset] | navdebug [on|off]" + ChatColor.GRAY + " - stuck/path-failure counters per map");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " swstats [reset|debug [on|off]]" + ChatColor.GRAY + " - SkyWars plans, fight origins, cage-drop confusion per map");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " tp <name|all> [player]" + ChatColor.GRAY + " - bring bot(s)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " goto <name>" + ChatColor.GRAY + " - teleport to bot");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " say <name> <message>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " walk <name> <fwd> <strafe> [sprint] | stop | jump | sneak <name>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " aim|attack <name> <player>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " aimpos <name> <x> <y> <z> | left <name> [hold|cps|click|off] | right <name> [click|hold|off] | slot <name> <0-8>");
    }

    private static String cheatNames() {
        StringBuilder b = new StringBuilder();
        for (CheatProfile c : CheatProfile.values()) {
            if (b.length() > 0) b.append('|');
            b.append(c.name().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    private static CheatProfile parseCheat(String s) {
        try {
            return CheatProfile.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown cheat profile '" + s + "' (" + cheatNames() + ")");
        }
    }

    private Location origin(CommandSender s) {
        if (s instanceof Player) return ((Player) s).getLocation();
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private FakeBot need(String name) {
        FakeBot b = bots.byName(name);
        if (b == null) throw new IllegalArgumentException("No bot named " + name);
        return b;
    }

    private List<FakeBot> targets(String nameOrAll) {
        if (nameOrAll.equalsIgnoreCase("all")) return bots.all();
        return Collections.singletonList(need(nameOrAll));
    }

    // ------------------------------------------------------------------ subcommands
    private void spawn(final CommandSender s, String[] args) {
        String nameArg = args.length > 1 ? args[1] : "random";
        final CheatProfile cheat = args.length > 2 ? parseCheat(args[2]) : CheatProfile.NONE;
        final Location base = origin(s);
        if (nameArg.startsWith("#")) {
            final int count;
            try { count = Math.max(1, Math.min(200, Integer.parseInt(nameArg.substring(1)))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad count " + nameArg); }
            s.sendMessage(P + ChatColor.GREEN + "Spawning " + count + " bots...");
            new BukkitRunnable() {
                int left = count;
                @Override public void run() {
                    if (left-- <= 0 || !plugin.isEnabled()) { cancel(); return; }
                    Location at = base.clone().add(rnd.nextGaussian() * 2.0D, 0.0D, rnd.nextGaussian() * 2.0D);
                    at.setY(base.getY());
                    at.setYaw((float) (rnd.nextDouble() * 360.0D - 180.0D));
                    at.setPitch(0.0F);
                    try { bots.spawn(bots.randomProfile(cheat), at); }
                    catch (RuntimeException e) { s.sendMessage(P + ChatColor.RED + "Spawn failed: " + e.getMessage()); }
                }
            }.runTaskTimer(plugin, 1L, 3L);
            return;
        }
        BotProfile profile;
        if (nameArg.equalsIgnoreCase("random") || nameArg.equals("-")) profile = bots.randomProfile(cheat);
        else {
            if (!nameArg.matches("^[A-Za-z0-9_]{3,16}$")) throw new IllegalArgumentException("Invalid username " + nameArg);
            if (!bots.nameFree(nameArg)) throw new IllegalArgumentException(nameArg + " is already online");
            profile = bots.profileNamed(nameArg, cheat);
        }
        bots.spawn(profile, base);
        s.sendMessage(P + ChatColor.GREEN + "Spawned " + ChatColor.WHITE + profile.name + ChatColor.GRAY + " (" + profile.rank
                + ", lvl " + profile.networkLevel + ", " + profile.pingMs + "ms, skill " + String.format("%.2f", profile.skill.overall)
                + ", cps " + String.format("%.1f", profile.skill.cps) + ", " + profile.skill.bridgeStyle
                + (cheat != CheatProfile.NONE ? ", " + ChatColor.RED + cheat : "") + ChatColor.GRAY + ")");
    }

    private void remove(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot remove <name|all>");
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.remove();
        s.sendMessage(P + ChatColor.GREEN + "Removed " + list.size() + " bot(s).");
    }

    private void list(CommandSender s) {
        List<FakeBot> all = bots.all();
        s.sendMessage(P + ChatColor.YELLOW + all.size() + " bot(s) online" + ChatColor.GRAY + " (skins in pool: " + bots.skins().size() + ")");
        StringBuilder line = new StringBuilder();
        for (FakeBot b : all) {
            BotProfile p = b.profile();
            if (line.length() > 0) line.append(ChatColor.GRAY).append(", ");
            line.append(p.cheat != CheatProfile.NONE ? ChatColor.RED : ChatColor.WHITE).append(p.name)
                    .append(ChatColor.DARK_GRAY).append('[').append(b.brain() == null ? "idle" : b.brain().toString())
                    .append(']');
            if (line.length() > 200) { s.sendMessage(line.toString()); line.setLength(0); }
        }
        if (line.length() > 0) s.sendMessage(line.toString());
    }

    private void cheat(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot cheat <name|all> <profile>");
        CheatProfile c = parseCheat(args[2]);
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.setCheat(c);
        s.sendMessage(P + ChatColor.GREEN + "Cheat profile " + ChatColor.RED + c + ChatColor.GREEN + " set on " + list.size() + " bot(s).");
    }

    private void look(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot look <name>");
        FakeBot b = need(args[1]);
        BotProfile p = b.profile();
        s.sendMessage(P + ChatColor.YELLOW + p.name + ChatColor.GRAY + " rank=" + p.rank + " skill=" + String.format("%.2f", p.skill.overall)
                + " aim=" + String.format("%.2f", p.skill.aim) + " react=" + p.skill.reactionMs + "ms sens=" + String.format("%.2f", p.skill.sensitivity)
                + " brain=" + (b.brain() == null ? "idle" : b.brain().toString()));
        for (String line : b.client.debug().split("\n")) s.sendMessage(ChatColor.GRAY + line);
    }

    private void tp(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot tp <name|all> [player]");
        Location to;
        if (args.length > 2) {
            Player t = Bukkit.getPlayerExact(args[2]);
            if (t == null) throw new IllegalArgumentException("Player " + args[2] + " not online");
            to = t.getLocation();
        } else {
            to = origin(s);
        }
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.player().teleport(to);
        s.sendMessage(P + ChatColor.GREEN + "Teleported " + list.size() + " bot(s).");
    }

    private void goTo(CommandSender s, String[] args) {
        if (!(s instanceof Player)) throw new IllegalArgumentException("Players only");
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot goto <name>");
        ((Player) s).teleport(need(args[1]).player().getLocation());
    }

    private void say(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot say <name> <message>");
        FakeBot b = need(args[1]);
        String msg = join(args, 2);
        if (msg.startsWith("/")) b.controller().command(msg);
        else b.controller().chat(msg);
    }

    private void walk(CommandSender s, String[] args) {
        if (args.length < 4) throw new IllegalArgumentException("Usage: /bot walk <name> <forward -1..1> <strafe -1..1> [sprint]");
        float f, st;
        try { f = Float.parseFloat(args[2]); st = Float.parseFloat(args[3]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
        for (FakeBot b : targets(args[1])) {
            b.setBrain(null);
            b.controller().setMovement(f, st);
            b.controller().setSprinting(args.length > 4 && Boolean.parseBoolean(args[4]));
        }
    }

    private void stop(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot stop <name|all>");
        for (FakeBot b : targets(args[1])) {
            b.setBrain(null);
            VirtualClient c = b.client;
            c.setMovement(0, 0);
            c.setJumping(false);
            c.setSneaking(false);
            c.setSprinting(false);
            c.setLeftHeld(false, 0);
            c.setRightHeld(false);
            c.clearAim();
        }
    }

    private void toggle(CommandSender s, String[] args, String what) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot " + what + " <name>");
        FakeBot b = need(args[1]);
        if (what.equals("jump")) {
            boolean on = !jumpState(b);
            b.client.setJumping(on);
            jumpFlags.put(b, on);
        } else {
            boolean on = !sneakState(b);
            b.client.setSneaking(on);
            sneakFlags.put(b, on);
        }
    }

    private final java.util.WeakHashMap<FakeBot, Boolean> jumpFlags = new java.util.WeakHashMap<FakeBot, Boolean>();
    private final java.util.WeakHashMap<FakeBot, Boolean> sneakFlags = new java.util.WeakHashMap<FakeBot, Boolean>();
    private boolean jumpState(FakeBot b) { Boolean v = jumpFlags.get(b); return v != null && v; }
    private boolean sneakState(FakeBot b) { Boolean v = sneakFlags.get(b); return v != null && v; }

    private void aim(CommandSender s, String[] args, boolean attack) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot " + (attack ? "attack" : "aim") + " <name> <player>");
        FakeBot b = need(args[1]);
        Player t = Bukkit.getPlayerExact(args[2]);
        if (t == null) throw new IllegalArgumentException("Player " + args[2] + " not online");
        b.setBrain(null);
        b.controller().aimAt(t, attack ? AimIntent.COMBAT : AimIntent.PRECISE);
        if (attack) b.controller().setLeftHeld(true, b.profile().skill.cps);
    }

    private void aimPos(CommandSender s, String[] args) {
        if (args.length < 5) throw new IllegalArgumentException("Usage: /bot aimpos <name> <x> <y> <z>");
        FakeBot b = need(args[1]);
        double x, y, z;
        try { x = Double.parseDouble(args[2]); y = Double.parseDouble(args[3]); z = Double.parseDouble(args[4]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
        b.setBrain(null);
        b.controller().aimAt(new Location(b.player().getWorld(), x, y, z), AimIntent.PRECISE);
    }

    private void left(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot left <name> [hold|cps|click|off]");
        FakeBot b = need(args[1]);
        String mode = args.length > 2 ? args[2] : "hold";
        if (mode.equalsIgnoreCase("off")) b.controller().setLeftHeld(false, 0);
        else if (mode.equalsIgnoreCase("click")) b.controller().leftClick();
        else if (mode.equalsIgnoreCase("hold")) b.controller().setLeftHeld(true, 0);
        else {
            try { b.controller().setLeftHeld(true, Double.parseDouble(mode)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("cps number, off, click or hold"); }
        }
    }

    private void right(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot right <name> [click|hold|off]");
        FakeBot b = need(args[1]);
        String mode = args.length > 2 ? args[2] : "click";
        if (mode.equalsIgnoreCase("hold")) b.controller().setRightHeld(true);
        else if (mode.equalsIgnoreCase("off")) b.controller().setRightHeld(false);
        else b.controller().rightClick();
    }

    private void slot(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot slot <name> <0-8>");
        try { need(args[1]).controller().selectHotbar(Integer.parseInt(args[2])); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Slot 0-8"); }
    }

    private static String join(String[] a, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (i > from) b.append(' ');
            b.append(a[i]);
        }
        return b.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("spawn")) {
            if ("all".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("all");
            for (FakeBot b : bots.all()) {
                String n = b.profile().name;
                if (n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(n);
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("cheat") || args[0].equalsIgnoreCase("spawn"))) {
            for (CheatProfile c : CheatProfile.values()) {
                String n = c.name().toLowerCase(Locale.ROOT);
                if (n.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(n);
            }
        } else if (args.length == 3) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
