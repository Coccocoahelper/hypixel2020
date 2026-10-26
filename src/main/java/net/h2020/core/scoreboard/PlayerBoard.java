package net.h2020.core.scoreboard;

import org.bukkit.ChatColor;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * One viewer's private scoreboard. The sidebar uses 15 fixed invisible entries (§0§r .. §e§r), each owned by a team
 * whose prefix/suffix carry the line text. Changing a line only updates a team (no score removal), so it never flickers.
 */
final class PlayerBoard {
    static final int MAX_LINES = 15;
    private static final ChatColor[] CODES = {
            ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_AQUA, ChatColor.DARK_RED,
            ChatColor.DARK_PURPLE, ChatColor.GOLD, ChatColor.GRAY, ChatColor.DARK_GRAY, ChatColor.BLUE,
            ChatColor.GREEN, ChatColor.AQUA, ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW};

    final Scoreboard board;
    private Objective side;
    private String title;
    private final String[] prefixes = new String[MAX_LINES];
    private final String[] suffixes = new String[MAX_LINES];
    private int shown = 0;

    PlayerBoard(Scoreboard board) { this.board = board; }

    private static String entry(int i) { return CODES[i].toString() + ChatColor.RESET; }

    void setSidebar(String newTitle, List<String> lines) {
        if (newTitle == null) newTitle = "";
        if (newTitle.length() > 32) newTitle = newTitle.substring(0, 32);
        if (side == null) {
            side = board.getObjective("h2020side");
            if (side == null) side = board.registerNewObjective("h2020side", "dummy");
            side.setDisplayName(newTitle);
            side.setDisplaySlot(DisplaySlot.SIDEBAR);
            title = newTitle;
            shown = 0;
        } else if (!newTitle.equals(title)) {
            side.setDisplayName(newTitle);
            title = newTitle;
        }
        int n = Math.min(MAX_LINES, lines.size());
        for (int i = 0; i < n; i++) {
            String text = lines.get(i) == null ? "" : lines.get(i);
            Team t = lineTeam(i);
            String[] split = split(text);
            if (!split[0].equals(prefixes[i])) { t.setPrefix(split[0]); prefixes[i] = split[0]; }
            if (!split[1].equals(suffixes[i])) { t.setSuffix(split[1]); suffixes[i] = split[1]; }
            side.getScore(entry(i)).setScore(n - i);
        }
        for (int i = n; i < shown; i++) board.resetScores(entry(i));
        shown = n;
    }

    void clearSidebar() {
        if (side != null) {
            try { side.unregister(); } catch (IllegalStateException ignored) {}
            side = null;
        }
        title = null;
        shown = 0;
    }

    private Team lineTeam(int i) {
        String name = "h2020_l" + i;
        Team t = board.getTeam(name);
        if (t == null) {
            t = board.registerNewTeam(name);
            t.addEntry(entry(i));
            prefixes[i] = "";
            suffixes[i] = "";
        }
        return t;
    }

    /** Split a line into a 16-char prefix and a 16-char suffix, carrying colours across the split. */
    static String[] split(String text) {
        if (text.length() <= 16) return new String[]{text, ""};
        int cut = 16;
        if (text.charAt(cut - 1) == ChatColor.COLOR_CHAR) cut--;
        String prefix = text.substring(0, cut);
        String rest = text.substring(cut);
        String carry = ChatColor.getLastColors(prefix);
        boolean restStartsWithColor = rest.length() >= 2 && rest.charAt(0) == ChatColor.COLOR_CHAR;
        String suffix = (restStartsWithColor ? "" : carry) + rest;
        if (suffix.length() > 16) {
            suffix = suffix.substring(0, 16);
            if (suffix.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) suffix = suffix.substring(0, 15);
        }
        return new String[]{prefix, suffix};
    }
}
