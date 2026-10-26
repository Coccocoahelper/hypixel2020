package net.h2020.api.game;

/** Hypixel 2020 queue modes. id() matches Hypixel's /play ids. */
public enum GameType {
    BEDWARS_SOLO(GameFamily.BEDWARS, "bedwars_eight_one", "Solo", 1, 8),
    BEDWARS_DOUBLES(GameFamily.BEDWARS, "bedwars_eight_two", "Doubles", 2, 8),
    BEDWARS_3V3V3V3(GameFamily.BEDWARS, "bedwars_four_three", "3v3v3v3", 3, 4),
    BEDWARS_4V4V4V4(GameFamily.BEDWARS, "bedwars_four_four", "4v4v4v4", 4, 4),
    SKYWARS_SOLO_NORMAL(GameFamily.SKYWARS, "solo_normal", "Solo Normal", 1, 12),
    SKYWARS_SOLO_INSANE(GameFamily.SKYWARS, "solo_insane", "Solo Insane", 1, 12),
    SKYWARS_TEAMS_NORMAL(GameFamily.SKYWARS, "teams_normal", "Teams Normal", 2, 8),
    SKYWARS_TEAMS_INSANE(GameFamily.SKYWARS, "teams_insane", "Teams Insane", 2, 8);

    public final GameFamily family;
    private final String id;
    public final String displayName;
    public final int teamSize;
    public final int teams;

    GameType(GameFamily family, String id, String displayName, int teamSize, int teams) {
        this.family = family; this.id = id; this.displayName = displayName; this.teamSize = teamSize; this.teams = teams;
    }

    public String id() { return id; }
    public int maxPlayers() { return teamSize * teams; }
    public boolean insane() { return this == SKYWARS_SOLO_INSANE || this == SKYWARS_TEAMS_INSANE; }

    public static GameType byId(String id) {
        for (GameType t : values()) if (t.id.equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) return t;
        return null;
    }
}
