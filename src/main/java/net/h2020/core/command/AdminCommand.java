package net.h2020.core.command;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.core.Rank;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.core.Core;
import net.h2020.core.CoreConfig;
import net.h2020.core.CoreUtil;
import net.h2020.core.HypixelFormat;
import net.h2020.core.lobby.npc.NpcManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** /h2020 — admin and video tools. */
public final class AdminCommand implements TabExecutor {
    private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{2}");
    private static final List<String> SUBS = Arrays.asList("games", "create", "start", "fill", "bots", "watch", "reload", "date",
            "rank", "level", "build", "setspawn", "npc", "cheaters", "fillspeed", "background");
    private final Core core;

    public AdminCommand(Core core) { this.core = core; }

    private static String g(String s) { return ChatColor.GREEN + s; }
    private static String r(String s) { return ChatColor.RED + s; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return true; }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "games": games(s); return true;
            case "create": {
                if (a.length < 2) { s.sendMessage(r("/h2020 create <type> [map]")); return true; }
                GameType t = GameType.byId(a[1]);
                if (t == null) { s.sendMessage(r("Unknown type " + a[1])); return true; }
                Game game;
                if (a.length >= 3) {
                    MapService ms = H2020.find(MapService.class);
                    MapTemplate m = ms == null ? null : ms.byName(a[2]);
                    if (m == null) { s.sendMessage(r("Unknown map " + a[2])); return true; }
                    game = core.games.create(t, m);
                } else game = core.games.createRandom(t);
                s.sendMessage(game == null ? r("Could not create a " + t.id() + " game (provider/maps missing?)")
                        : g("Created " + game.id() + " (" + t.id() + " on " + game.mapName() + ")"));
                return true;
            }
            case "start": {
                if (a.length < 2) { s.sendMessage(r("/h2020 start <gameId>")); return true; }
                Game game = core.games.byId(a[1]);
                if (game == null) { s.sendMessage(r("No game " + a[1])); return true; }
                if (game.state() != GameState.WAITING && game.state() != GameState.STARTING) { s.sendMessage(r(game.id() + " already started.")); return true; }
                if (H2020.find(BotService.class) == null) { s.sendMessage(r("Bots module not loaded.")); return true; }
                int n = core.filler.fillNow(game, Integer.MAX_VALUE);
                s.sendMessage(g("Filling " + game.id() + " with " + n + " bots; it starts when full."));
                return true;
            }
            case "fill": {
                if (a.length < 2) { s.sendMessage(r("/h2020 fill <type> [count]")); return true; }
                GameType t = GameType.byId(a[1]);
                if (t == null) { s.sendMessage(r("Unknown type " + a[1])); return true; }
                Game game = core.games.findJoinable(t);
                if (game == null) game = core.games.createRandom(t);
                if (game == null) { s.sendMessage(r("Could not create a " + t.id() + " game.")); return true; }
                int count = a.length >= 3 ? parseInt(a[2], Integer.MAX_VALUE) : Integer.MAX_VALUE;
                int n = core.filler.fillNow(game, count);
                s.sendMessage(g("Added " + n + " bots to " + game.id() + " (" + game.mapName() + ")."));
                return true;
            }
            case "join": {
                // put any online player (human or bot) into a game of that type, e.g. for recording or testing
                if (a.length < 3) { s.sendMessage(r("/h2020 join <player> <type>")); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                GameType t = GameType.byId(a[2]);
                if (target == null || t == null) { s.sendMessage(r("Unknown player or type.")); return true; }
                Game game = core.games.queue(target, t);
                s.sendMessage(game == null ? r("Could not queue " + target.getName() + ".")
                        : g("Queued " + target.getName() + " into " + game.id() + " (" + game.mapName() + ")."));
                return true;
            }
            case "bots": {
                if (a.length < 2) { s.sendMessage(r("/h2020 bots <n> [main|bedwars|skywars]")); return true; }
                int n = Math.max(0, parseInt(a[1], -1));
                World w;
                if (a.length >= 3) w = core.lobby.lobbyWorld(family(a[2]));
                else if (s instanceof Player && core.lobby.isLobby(((Player) s).getWorld())) w = ((Player) s).getWorld();
                else w = core.lobby.lobbyWorld(null);
                int diff = core.lobbyBots.setCount(w, n);
                s.sendMessage(g("Lobby " + w.getName() + ": " + (diff >= 0 ? "+" : "") + diff + " bots (target " + n + ")."));
                return true;
            }
            case "watch": {
                if (!(s instanceof Player)) return true;
                if (a.length < 2) { s.sendMessage(r("/h2020 watch <gameId>")); return true; }
                Game game = core.games.byId(a[1]);
                if (game == null) { s.sendMessage(r("No game " + a[1])); return true; }
                Player p = (Player) s;
                Game cur = core.games.gameOf(p);
                if (cur != null) core.games.leaveGame(p, cur);
                core.lobby.leaving(p);
                core.scoreboards.clearSidebar(p);
                p.getInventory().clear();
                p.setGameMode(GameMode.SPECTATOR);
                Location to = game.spectatorSpawn();
                p.teleport(to != null ? to : game.world().getSpawnLocation());
                p.sendMessage(g("You are now spectating " + game.id() + " (" + game.mapName() + "). Use /lobby to leave."));
                return true;
            }
            case "reload": {
                core.plugin.reloadConfig();
                core.config.load(core.plugin);
                net.h2020.core.CoreModule.applyDate(core.config.date);
                if (core.npcs != null) core.npcs.rebuild();
                for (Player p : Bukkit.getOnlinePlayers()) if (!CoreUtil.isBot(p)) core.scoreboards.setTabHeaderFooter(p, core.config.tabHeader, core.config.tabFooter);
                s.sendMessage(g("Hypixel2020 core config reloaded."));
                return true;
            }
            case "date": {
                if (a.length < 2) { s.sendMessage(g("Scoreboard date: " + HypixelFormat.date())); return true; }
                if (a[1].equalsIgnoreCase("2020")) a[1] = "07/14/20";   // back to the 2020 look
                if (a[1].equalsIgnoreCase("today")) a[1] = "today";     // follows the real date
                else if (!DATE.matcher(a[1]).matches()) { s.sendMessage(r("Format: MM/dd/yy (e.g. 07/14/20), 'today' or '2020'")); return true; }
                core.config.saveDate(a[1]);
                net.h2020.core.CoreModule.applyDate(a[1]);
                s.sendMessage(g("Scoreboard date set to " + a[1] + (a[1].equals("today") ? " (" + HypixelFormat.date() + ")" : "") + ". New games use it; the lobby updates now."));
                return true;
            }
            case "rank": {
                if (a.length < 3) { s.sendMessage(r("/h2020 rank <player> <rank> [plusColor]")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                Rank rank = CoreConfig.parseEnum(Rank.class, a[2]);
                if (t == null || rank == null) { s.sendMessage(r("Unknown player or rank. Ranks: " + Arrays.toString(Rank.values()))); return true; }
                ChatColor plus = a.length >= 4 ? CoreConfig.parseEnum(ChatColor.class, a[3]) : null;
                core.profiles.setRank(t, rank, plus);
                s.sendMessage(g("Set " + t.getName() + " to ") + core.profiles.displayName(t));
                return true;
            }
            case "level": {
                if (a.length < 3) { s.sendMessage(r("/h2020 level <player> <level>")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                int lvl = parseInt(a[2], -1);
                if (t == null || lvl < 1) { s.sendMessage(r("Unknown player or bad level.")); return true; }
                core.profiles.setNetworkLevel(t, lvl);
                s.sendMessage(g("Set " + t.getName() + "'s network level to " + lvl));
                return true;
            }
            case "build": {
                if (!(s instanceof Player)) return true;
                Player p = (Player) s;
                boolean on = core.lobby.toggleBuild(p);
                if (on) { p.setGameMode(GameMode.CREATIVE); p.getInventory().clear(); }
                else core.games.sendToLobby(p, core.lobby.familyOf(p.getWorld()));
                s.sendMessage(on ? g("Lobby build mode enabled.") : r("Lobby build mode disabled."));
                return true;
            }
            case "setspawn": {
                if (!(s instanceof Player)) return true;
                Player p = (Player) s;
                if (!core.lobby.isLobby(p.getWorld())) { s.sendMessage(r("Stand in a lobby world.")); return true; }
                core.lobby.setSpawn(p.getLocation());
                if (core.npcs != null) core.npcs.rebuild();
                s.sendMessage(g("Lobby spawn for " + p.getWorld().getName() + " set."));
                return true;
            }
            case "npc": {
                if (!(s instanceof Player)) return true;
                if (a.length < 2) { s.sendMessage(r("/h2020 npc <id>  ids: " + NpcManager.ids())); return true; }
                boolean ok = core.npcs != null && core.npcs.move(a[1], ((Player) s).getLocation());
                s.sendMessage(ok ? g("NPC " + a[1] + " moved here.") : r("Unknown NPC. ids: " + NpcManager.ids()));
                return true;
            }
            case "cheaters": {
                if (a.length < 2) { s.sendMessage(g("Cheater %: " + core.config.cheaterPercent)); return true; }
                core.config.cheaterPercent = Math.max(0, Math.min(100, parseDouble(a[1], 0)));
                core.plugin.getConfig().set("core.bot-fill.cheater-percent", core.config.cheaterPercent);
                core.plugin.saveConfig();
                s.sendMessage(g("Queue-filler cheater percentage set to " + core.config.cheaterPercent + "%"));
                return true;
            }
            case "fillspeed": {
                if (a.length < 2) { s.sendMessage(g("Fill speed: " + core.config.fillSpeed)); return true; }
                core.config.fillSpeed = Math.max(0.05, parseDouble(a[1], 1));
                core.plugin.getConfig().set("core.bot-fill.speed", core.config.fillSpeed);
                core.plugin.saveConfig();
                s.sendMessage(g("Bot fill speed set to x" + core.config.fillSpeed));
                return true;
            }
            case "background": {
                if (a.length < 2) { s.sendMessage(g("Background games: " + core.config.backgroundGames)); return true; }
                core.config.backgroundGames = Math.max(0, parseInt(a[1], 0));
                core.plugin.getConfig().set("core.background-games", core.config.backgroundGames);
                core.plugin.saveConfig();
                s.sendMessage(g("Background bot-only games set to " + core.config.backgroundGames));
                return true;
            }
            default:
                help(s);
                return true;
        }
    }

    private void games(CommandSender s) {
        if (core.games.games().isEmpty()) { s.sendMessage(ChatColor.GRAY + "No running games."); return; }
        s.sendMessage(ChatColor.GOLD + "Running games:");
        for (Game game : core.games.games()) {
            int bots = 0;
            for (Player p : game.players()) if (CoreUtil.isBot(p)) bots++;
            int start = game.secondsUntilStart();
            s.sendMessage(ChatColor.YELLOW + game.id() + ChatColor.GRAY + " " + game.type().id() + " " + ChatColor.WHITE + game.mapName()
                    + ChatColor.GRAY + " [" + game.state() + (start >= 0 ? " " + start + "s" : "") + "] "
                    + ChatColor.GREEN + game.players().size() + "/" + game.type().maxPlayers()
                    + ChatColor.GRAY + " (" + bots + " bots, " + game.alivePlayers().size() + " alive)");
        }
    }

    private static void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "Hypixel2020 admin:");
        s.sendMessage(ChatColor.YELLOW + "/h2020 games" + ChatColor.GRAY + " - list running games");
        s.sendMessage(ChatColor.YELLOW + "/h2020 create <type> [map]" + ChatColor.GRAY + " - create a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 start <gameId>" + ChatColor.GRAY + " - fill with bots so it starts");
        s.sendMessage(ChatColor.YELLOW + "/h2020 fill <type> [n]" + ChatColor.GRAY + " - add bots to the fullest waiting game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 join <player> <type>" + ChatColor.GRAY + " - queue a player or bot into a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 bots <n> [lobby]" + ChatColor.GRAY + " - idle bots in a lobby");
        s.sendMessage(ChatColor.YELLOW + "/h2020 watch <gameId>" + ChatColor.GRAY + " - spectate a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 date [MM/dd/yy|today|2020]" + ChatColor.GRAY + " - scoreboard date");
        s.sendMessage(ChatColor.YELLOW + "/h2020 rank <player> <rank> [color] | level <player> <n>");
        s.sendMessage(ChatColor.YELLOW + "/h2020 cheaters <%> | fillspeed <x> | background <n>");
        s.sendMessage(ChatColor.YELLOW + "/h2020 build | setspawn | npc <id> | reload");
    }

    private static Integer parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private static net.h2020.api.game.GameFamily family(String s) {
        String a = s.toLowerCase();
        if (a.startsWith("b")) return net.h2020.api.game.GameFamily.BEDWARS;
        if (a.startsWith("s")) return net.h2020.api.game.GameFamily.SKYWARS;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : SUBS) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (sub.equals("create") || sub.equals("fill")) {
                for (GameType t : GameType.values()) if (t.id().startsWith(a[1].toLowerCase())) out.add(t.id());
            } else if (sub.equals("start") || sub.equals("watch")) {
                for (Game game : core.games.games()) if (game.id().toLowerCase().startsWith(a[1].toLowerCase())) out.add(game.id());
            } else if (sub.equals("npc")) {
                for (String id : NpcManager.ids()) if (id.startsWith(a[1].toLowerCase())) out.add(id);
            } else if (sub.equals("rank") || sub.equals("level")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("rank")) {
            for (Rank rk : Rank.values()) if (rk.name().toLowerCase().startsWith(a[2].toLowerCase())) out.add(rk.name());
        }
        return out;
    }
}
