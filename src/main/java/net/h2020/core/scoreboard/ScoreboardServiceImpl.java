package net.h2020.core.scoreboard;

import net.h2020.api.core.ScoreboardService;
import net.h2020.core.CoreUtil;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerListHeaderFooter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-viewer Bukkit scoreboards (humans only; bots have no client to render them). Name tags are scoreboard teams
 * mirrored into every viewer's board, so everyone sees the same tag; the team name also sorts the tab list
 * (staff/ranks first, then game teams). Main thread only.
 */
public final class ScoreboardServiceImpl implements ScoreboardService, Listener {
    private static final String NPC_TEAM = "zzh2020npc";
    private static Field footerField;

    private final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private final Map<UUID, Tag> tags = new HashMap<>();
    /** first-seen order of non-rank prefixes -> sort group */
    private final Map<String, Integer> prefixGroups = new HashMap<>();
    private final Set<String> hiddenEntries = new LinkedHashSet<>();
    private int idCounter = 0;
    private String defaultHeader = "", defaultFooter = "";

    /** A target's nametag. */
    private static final class Tag {
        final String entry;
        final int id;
        String team, prefix, suffix;
        Tag(String entry, int id) { this.entry = entry; this.id = id; }
    }

    public void setDefaultTab(String header, String footer) { this.defaultHeader = header; this.defaultFooter = footer; }

    // ---- lifecycle ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!CoreUtil.isBot(p)) {
            board(p);
            setTabHeaderFooter(p, defaultHeader, defaultFooter);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer()); }

    public void forget(Player p) {
        boards.remove(p.getUniqueId());
        Tag t = tags.remove(p.getUniqueId());
        if (t != null && t.team != null) {
            for (PlayerBoard b : boards.values()) {
                Team team = b.board.getTeam(t.team);
                if (team != null) team.unregister();
            }
        }
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (boards.containsKey(p.getUniqueId())) {
                try { p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); } catch (Throwable ignored) {}
            }
        }
        boards.clear();
        tags.clear();
    }

    /** The viewer's private board, created (and populated with every nametag) on first use. Null for bots. */
    private PlayerBoard board(Player viewer) {
        PlayerBoard b = boards.get(viewer.getUniqueId());
        if (b != null) {
            if (viewer.getScoreboard() != b.board) viewer.setScoreboard(b.board);
            return b;
        }
        if (CoreUtil.isBot(viewer)) return null;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        b = new PlayerBoard(sb);
        boards.put(viewer.getUniqueId(), b);
        for (Tag t : tags.values()) if (t.team != null) apply(sb, t);
        Team npc = sb.registerNewTeam(NPC_TEAM);
        npc.setNameTagVisibility(NameTagVisibility.NEVER);
        for (String s : hiddenEntries) npc.addEntry(s);
        viewer.setScoreboard(sb);
        return b;
    }

    // ---- sidebar ----

    @Override
    public void setSidebar(Player viewer, String title, List<String> lines) {
        PlayerBoard b = board(viewer);
        if (b == null) return;
        b.setSidebar(title, lines == null ? new ArrayList<String>() : lines);
    }

    @Override
    public void clearSidebar(Player viewer) {
        PlayerBoard b = boards.get(viewer.getUniqueId());
        if (b != null) b.clearSidebar();
    }

    // ---- nametags ----

    @Override
    public void setNametag(Player target, String prefix, String suffix, ChatColor nameColor) {
        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";
        if (nameColor != null) {
            String c = nameColor.toString();
            if (!prefix.endsWith(c) && !ChatColor.getLastColors(prefix).equals(c)) {
                if (prefix.length() + 2 > 16) prefix = prefix.substring(0, 14);
                prefix = prefix + c;
            }
        }
        if (prefix.length() > 16) prefix = prefix.substring(0, 16);
        if (suffix.length() > 16) suffix = suffix.substring(0, 16);
        Tag t = tags.get(target.getUniqueId());
        if (t == null) {
            t = new Tag(target.getName(), idCounter++);
            tags.put(target.getUniqueId(), t);
        }
        String team = teamName(prefix, t.id);
        if (team.equals(t.team) && prefix.equals(t.prefix) && suffix.equals(t.suffix)) return;
        String oldTeam = t.team;
        t.team = team;
        t.prefix = prefix;
        t.suffix = suffix;
        for (PlayerBoard b : boards.values()) {
            if (oldTeam != null && !oldTeam.equals(team)) {
                Team old = b.board.getTeam(oldTeam);
                if (old != null) old.unregister();
            }
            apply(b.board, t);
        }
    }

    @Override
    public void resetNametag(Player target) {
        Tag t = tags.get(target.getUniqueId());
        if (t == null || t.team == null) return;
        for (PlayerBoard b : boards.values()) {
            Team team = b.board.getTeam(t.team);
            if (team != null) team.unregister();
        }
        t.team = null;
        t.prefix = null;
        t.suffix = null;
    }

    private static void apply(Scoreboard sb, Tag t) {
        Team team = sb.getTeam(t.team);
        if (team == null) {
            team = sb.registerNewTeam(t.team);
            team.setAllowFriendlyFire(true);
            team.setCanSeeFriendlyInvisibles(false);
        }
        if (!t.prefix.equals(team.getPrefix())) team.setPrefix(t.prefix);
        if (!t.suffix.equals(team.getSuffix())) team.setSuffix(t.suffix);
        if (!team.hasEntry(t.entry)) team.addEntry(t.entry);
    }

    /** Sort key: rank order for rank prefixes, else first-seen group of the prefix; then a stable id. */
    private String teamName(String prefix, int id) {
        String stripped = ChatColor.stripColor(prefix).trim();
        String group;
        if (stripped.startsWith("[ADMIN")) group = "a0";
        else if (stripped.startsWith("[YOUTUBE")) group = "a1";
        else if (stripped.startsWith("[MVP++")) group = "a2";
        else if (stripped.startsWith("[MVP+")) group = "a3";
        else if (stripped.startsWith("[MVP")) group = "a4";
        else if (stripped.startsWith("[VIP+")) group = "a5";
        else if (stripped.startsWith("[VIP")) group = "a6";
        else if (stripped.isEmpty()) group = "z9";
        else {
            Integer g = prefixGroups.get(prefix);
            if (g == null) { g = prefixGroups.size(); prefixGroups.put(prefix, g); }
            group = "g" + Integer.toString(g % 36, 36);
        }
        String n = group + Integer.toString(id, 36);
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    /** Hide the name tags of these entries (packet NPC names) for every viewer. */
    public void hideNametags(Collection<String> entries) {
        hiddenEntries.addAll(entries);
        for (PlayerBoard b : boards.values()) {
            Team t = b.board.getTeam(NPC_TEAM);
            if (t == null) {
                t = b.board.registerNewTeam(NPC_TEAM);
                t.setNameTagVisibility(NameTagVisibility.NEVER);
            }
            for (String s : entries) if (!t.hasEntry(s)) t.addEntry(s);
        }
    }

    // ---- tab ----

    @Override
    public void setTabHeaderFooter(Player viewer, String header, String footer) {
        if (CoreUtil.isBot(viewer)) return;
        PacketPlayOutPlayerListHeaderFooter packet = new PacketPlayOutPlayerListHeaderFooter(CoreUtil.text(header == null ? "" : header));
        try {
            if (footerField == null) {
                Field f = PacketPlayOutPlayerListHeaderFooter.class.getDeclaredField("b");
                f.setAccessible(true);
                footerField = f;
            }
            footerField.set(packet, CoreUtil.text(footer == null ? "" : footer));
        } catch (ReflectiveOperationException ex) {
            return;
        }
        CoreUtil.send(viewer, packet);
    }
}
