package net.h2020.core.lobby;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.GameFamily;
import net.h2020.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Idle bots standing around in each lobby (core.lobby-bots per lobby). They log in staggered after startup; the
 * bots-ai module gives them lobby brains. Lobbies that overflow from game churn are trimmed back down.
 */
public final class LobbyBots {
    private final Core core;

    public LobbyBots(Core core) { this.core = core; }

    public void start() {
        Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
            @Override public void run() {
                for (World w : lobbies()) setCount(w, core.config.lobbyBots);
            }
        }, 200L);
        Bukkit.getScheduler().runTaskTimer(core.plugin, new Runnable() {
            @Override public void run() { trim(); }
        }, 20L * 60, 20L * 30);
    }

    private List<World> lobbies() {
        List<World> l = new ArrayList<>();
        l.add(core.lobby.lobbyWorld(null));
        for (GameFamily f : GameFamily.values()) {
            World w = core.lobby.lobbyWorld(f);
            if (!l.contains(w)) l.add(w);
        }
        return l;
    }

    /** Idle bots currently in a lobby world (not in a game). */
    public List<Bot> botsIn(World w) {
        List<Bot> out = new ArrayList<>();
        BotService bs = H2020.find(BotService.class);
        if (bs == null) return out;
        for (Bot b : bs.bots()) {
            Player p = b.player();
            if (b.isRemoved() || p == null || !p.isOnline()) continue;
            if (p.getWorld().equals(w) && core.games.gameOf(p) == null) out.add(b);
        }
        return out;
    }

    /** Spawn or remove bots so that `target` idle bots are in this lobby. Returns the change. */
    public int setCount(final World w, int target) {
        final BotService bs = H2020.find(BotService.class);
        if (bs == null) return 0;
        List<Bot> current = botsIn(w);
        int diff = target - current.size();
        if (diff < 0) {
            Collections.shuffle(current);
            for (int i = 0; i < -diff; i++) {
                final Bot b = current.get(i);
                Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
                    @Override public void run() { if (!b.isRemoved()) b.remove(); }
                }, 5L + ThreadLocalRandom.current().nextInt(100));
            }
        } else {
            long delay = 1;
            for (int i = 0; i < diff; i++) {
                delay += 5 + ThreadLocalRandom.current().nextInt(30);
                Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
                    @Override public void run() {
                        try { bs.spawn(bs.randomProfile(), randomSpot(w)); }
                        catch (Throwable t) { core.plugin.getLogger().log(Level.WARNING, "Lobby bot spawn failed", t); }
                    }
                }, delay);
            }
        }
        return diff;
    }

    /** A walkable spot within ~8 blocks of the lobby spawn. */
    private Location randomSpot(World w) {
        Location spawn = core.lobby.spawn(w);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 20; attempt++) {
            double x = spawn.getX() + r.nextDouble(-8, 8), z = spawn.getZ() + r.nextDouble(-8, 8);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            int top = spawn.getBlockY() + 3;
            for (int y = top; y > top - 8 && y > 1; y--) {
                Block below = w.getBlockAt(bx, y - 1, bz);
                if (below.getType().isSolid() && !w.getBlockAt(bx, y, bz).getType().isSolid() && !w.getBlockAt(bx, y + 1, bz).getType().isSolid()) {
                    return new Location(w, bx + 0.5, y, bz + 0.5, r.nextFloat() * 360f - 180f, 0f);
                }
            }
        }
        return spawn;
    }

    /** Lobbies fill up with bots that left games; drift them back towards ~2x the configured count. */
    private void trim() {
        int cap = Math.max(10, core.config.lobbyBots * 2);
        for (World w : lobbies()) {
            List<Bot> in = botsIn(w);
            if (in.size() <= cap) continue;
            Collections.shuffle(in);
            int remove = Math.min(3, in.size() - cap);
            for (int i = 0; i < remove; i++) {
                final Bot b = in.get(i);
                Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
                    @Override public void run() { if (!b.isRemoved()) b.remove(); }
                }, ThreadLocalRandom.current().nextInt(200));
            }
        }
    }
}
