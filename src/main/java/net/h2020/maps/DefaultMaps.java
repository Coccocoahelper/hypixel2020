package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.maps.gen.BedwarsGenerator;
import net.h2020.maps.gen.GeneratedMap;
import net.h2020.maps.gen.SkywarsGenerator;
import net.h2020.maps.gen.Themes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The built-in procedural map rotation (original names, Hypixel-like themes). */
public final class DefaultMaps {
    /** Bump when generator output changes: generated templates with an older version are rebuilt on start. */
    public static final int VERSION = 1;

    public static final class Def {
        public final String name, theme;
        public final GameFamily family;
        public final int teams;

        Def(String name, GameFamily family, int teams, String theme) {
            this.name = name; this.family = family; this.teams = teams; this.theme = theme;
        }

        public String folder() { return name.toLowerCase().replaceAll("[^a-z0-9]+", "_"); }

        public long seed() { return ((long) ("h2020:" + name).hashCode() << 16) ^ (teams * 7919L) ^ VERSION; }

        public GeneratedMap generate() {
            if (family == GameFamily.BEDWARS) return BedwarsGenerator.generate(name, Themes.byId(theme), teams, seed());
            return SkywarsGenerator.generate(name, Themes.byId(theme), teams, seed());
        }
    }

    private static final List<Def> ALL;

    static {
        List<Def> l = new ArrayList<Def>();
        // Bed Wars 8 teams (Solo / Doubles)
        l.add(new Def("Beacon Point", GameFamily.BEDWARS, 8, "coastal"));
        l.add(new Def("Deepwater", GameFamily.BEDWARS, 8, "aquarium"));
        l.add(new Def("Verdance", GameFamily.BEDWARS, 8, "jungle"));
        l.add(new Def("Cinderforge", GameFamily.BEDWARS, 8, "nether"));
        // Bed Wars 4 teams (3v3v3v3 / 4v4v4v4)
        l.add(new Def("Saltmarsh", GameFamily.BEDWARS, 4, "coastal"));
        l.add(new Def("Glasshaven", GameFamily.BEDWARS, 4, "aquarium"));
        l.add(new Def("Overgrowth", GameFamily.BEDWARS, 4, "jungle"));
        l.add(new Def("Brimstone", GameFamily.BEDWARS, 4, "nether"));
        // SkyWars solo (12 islands)
        l.add(new Def("Mistvale", GameFamily.SKYWARS, 12, "meadow"));
        l.add(new Def("Sandspire", GameFamily.SKYWARS, 12, "desert"));
        l.add(new Def("Frostpeak", GameFamily.SKYWARS, 12, "snow"));
        // SkyWars teams (8 islands, cages for 2)
        l.add(new Def("Cobbleshire", GameFamily.SKYWARS, 8, "shire"));
        l.add(new Def("Driftwood", GameFamily.SKYWARS, 8, "coastal"));
        ALL = Collections.unmodifiableList(l);
    }

    private DefaultMaps() {}

    public static List<Def> all() { return ALL; }

    public static Def byName(String name) {
        for (Def d : ALL) if (d.name.equalsIgnoreCase(name)) return d;
        return null;
    }
}
