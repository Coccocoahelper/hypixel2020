package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.maps.gen.GeneratedMap;
import net.h2020.maps.gen.LobbyGenerator;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MapService implementation. On start: cleans stale instance folders, loads every template folder under
 * plugins/Hypixel2020/maps, generates the default procedural rotation for any (family, team count) group that
 * has no maps, then loads (or generates once) the three hub worlds. Everything happens synchronously during
 * module enable, so templates are ready before any game can be created.
 *
 * Optional config section "maps": always-generate-defaults (false), regenerate (false),
 * main-lobby-world / bedwars-lobby-world / skywars-lobby-world (use an existing world folder instead).
 */
public final class MapServiceImpl implements MapService {
    static final int LOBBY_VERSION = 1;

    final JavaPlugin plugin;
    private final Logger log;
    final File mapsDir;
    private final Map<String, StoredMapTemplate> templates = new LinkedHashMap<String, StoredMapTemplate>();
    /** instance world name (lower case) -> template */
    final Map<String, StoredMapTemplate> instances = new ConcurrentHashMap<String, StoredMapTemplate>();
    private World mainLobby, bedwarsLobby, skywarsLobby;
    private volatile boolean ready;

    public MapServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.mapsDir = new File(plugin.getDataFolder(), "maps");
    }

    // ============================================================================================ lifecycle
    public void start() {
        long t0 = System.currentTimeMillis();
        if (!mapsDir.isDirectory() && !mapsDir.mkdirs()) log.warning("[maps] could not create " + mapsDir);
        writeFormatHelp();
        cleanupStale();
        loadAll();
        generateMissing();
        loadLobbies();
        writeLobbyNpcs();
        ready = true;
        StringBuilder sb = new StringBuilder();
        for (StoredMapTemplate t : templates.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name).append(' ').append(t.teamCounts);
        }
        log.info("[maps] ready in " + (System.currentTimeMillis() - t0) + "ms: " + templates.size() + " templates (" + sb + ")");
    }

    public void shutdown() {
        ready = false;
        for (World w : new ArrayList<World>(Bukkit.getWorlds())) {
            StoredMapTemplate t = instances.get(w.getName().toLowerCase());
            if (t != null) {
                try { t.destroyNow(w); } catch (Throwable e) { log.log(Level.WARNING, "[maps] failed to remove " + w.getName(), e); }
            }
        }
        instances.clear();
    }

    public boolean isReady() { return ready; }

    private ConfigurationSection config() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("maps");
        return s != null ? s : new YamlConfiguration();
    }

    /** Remove instance folders and half-finished generation worlds left by a crash. */
    private void cleanupStale() {
        File inst = new File(Worlds.container(), Worlds.INSTANCE_DIR);
        if (inst.isDirectory()) {
            File[] files = inst.listFiles();
            if (files != null) for (File f : files) {
                if (Bukkit.getWorld(Worlds.INSTANCE_DIR + "/" + f.getName()) == null) Worlds.delete(f);
            }
        }
        File[] roots = Worlds.container().listFiles();
        if (roots != null) for (File f : roots)
            if (f.isDirectory() && f.getName().startsWith(Worlds.GEN_PREFIX) && Bukkit.getWorld(f.getName()) == null) Worlds.delete(f);
    }

    // ============================================================================================ templates
    private void loadAll() {
        templates.clear();
        File[] dirs = mapsDir.listFiles();
        if (dirs == null) return;
        java.util.Arrays.sort(dirs);
        boolean regenerate = config().getBoolean("regenerate", false);
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            if (!new File(dir, MapFiles.MAP_YML).isFile()) {
                if (new File(dir, "level.dat").isFile() || new File(dir, "world").isDirectory())
                    log.warning("[maps] " + dir.getName() + " has no map.yml - skipped (see maps/FORMAT.txt)");
                continue;
            }
            try {
                StoredMapTemplate t = MapFiles.read(this, dir);
                if (t.generated && (t.generatorVersion < DefaultMaps.VERSION || regenerate) && DefaultMaps.byName(t.name) != null) {
                    log.info("[maps] regenerating outdated generated map " + t.name);
                    Worlds.delete(dir);
                    continue;
                }
                if (!new File(t.worldFolder(), "level.dat").isFile()) {
                    log.warning("[maps] " + dir.getName() + ": missing world/level.dat - skipped");
                    continue;
                }
                register(t);
            } catch (Throwable e) {
                log.warning("[maps] could not load map " + dir.getName() + ": " + e.getMessage());
            }
        }
    }

    private void register(StoredMapTemplate t) {
        String key = t.name.toLowerCase(Locale.ROOT);
        if (templates.containsKey(key)) {
            log.warning("[maps] duplicate map name " + t.name + " in " + t.folder + " - skipped");
            return;
        }
        templates.put(key, t);
    }

    private void generateMissing() {
        boolean always = config().getBoolean("always-generate-defaults", false);
        for (DefaultMaps.Def d : DefaultMaps.all()) {
            if (templates.containsKey(d.name.toLowerCase(Locale.ROOT))) continue;
            if (!always && hasGroup(d.family, d.teams, d.name)) continue;
            try {
                StoredMapTemplate t = bake(d);
                if (t != null) register(t);
            } catch (Throwable e) {
                log.log(Level.SEVERE, "[maps] failed to generate " + d.name, e);
            }
        }
    }

    /** True if a non-default map (or an already present default) serves this family/team-count group. */
    private boolean hasGroup(GameFamily family, int teams, String excluding) {
        for (StoredMapTemplate t : templates.values()) {
            if (t.family != family || !t.teamCounts.contains(teams)) continue;
            if (t.generated && DefaultMaps.byName(t.name) != null) continue; // defaults never block other defaults
            if (!t.name.equalsIgnoreCase(excluding)) return true;
        }
        return false;
    }

    /** Generate one default map: paint, write into a scratch world, save, move into maps/<folder>/world. */
    private StoredMapTemplate bake(DefaultMaps.Def d) throws IOException {
        long t0 = System.currentTimeMillis();
        log.info("[maps] generating " + d.family + " map '" + d.name + "' (" + d.teams + " teams, " + d.theme + ")...");
        GeneratedMap gm = d.generate();
        File target = new File(mapsDir, d.folder());
        Worlds.delete(target);
        if (!target.mkdirs() && !target.isDirectory()) throw new IOException("cannot create " + target);
        String scratch = Worlds.GEN_PREFIX + d.folder();
        File scratchDir = new File(Worlds.container(), scratch);
        Worlds.delete(scratchDir);
        writeWorld(gm, scratch);
        Worlds.copyWorld(scratchDir, new File(target, "world"));
        Worlds.delete(scratchDir);
        MapFiles.write(gm, target, DefaultMaps.VERSION, d.seed());
        log.info("[maps] generated '" + d.name + "' (" + gm.canvas.size() + " blocks) in " + (System.currentTimeMillis() - t0) + "ms");
        return MapFiles.read(this, target);
    }

    /** Create a void world, flush the canvas into it, save fully and unload. */
    private void writeWorld(GeneratedMap gm, String worldName) {
        World w = Worlds.loadVoid(worldName, gm.biome, gm.spawn[0], gm.spawn[1], gm.spawn[2]);
        if (w == null) throw new IllegalStateException("could not create scratch world " + worldName);
        try {
            Worlds.applyRules(w, true);
            w.setAutoSave(true);
            gm.canvas.flush(w, log);
            w.setSpawnLocation((int) Math.floor(gm.spawn[0]), (int) Math.floor(gm.spawn[1]), (int) Math.floor(gm.spawn[2]));
            Worlds.saveFully(w, log);
        } finally {
            if (!Bukkit.unloadWorld(w, false)) log.warning("[maps] could not unload scratch world " + worldName);
        }
    }

    // ============================================================================================ lobbies
    private void loadLobbies() {
        ConfigurationSection cfg = config();
        mainLobby = lobby(cfg.getString("main-lobby-world", ""), LobbyGenerator.MAIN, 0);
        bedwarsLobby = lobby(cfg.getString("bedwars-lobby-world", ""), LobbyGenerator.BEDWARS, 1);
        skywarsLobby = lobby(cfg.getString("skywars-lobby-world", ""), LobbyGenerator.SKYWARS, 2);
    }

    private World lobby(String override, String name, int kind) {
        try {
            if (override != null && !override.isEmpty()) {
                World w = Bukkit.getWorld(override);
                if (w == null && new File(Worlds.container(), override + "/level.dat").isFile()) w = Bukkit.createWorld(new org.bukkit.WorldCreator(override));
                if (w != null) { Worlds.applyRules(w, false); return w; }
                log.warning("[maps] configured lobby world '" + override + "' not found, using generated " + name);
            }
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) return loaded;
            File dir = new File(Worlds.container(), name);
            File marker = new File(dir, "h2020-lobby.yml");
            boolean fresh = !marker.isFile() || YamlConfiguration.loadConfiguration(marker).getInt("version", 0) < LOBBY_VERSION;
            GeneratedMap gm = null;
            if (fresh) {
                log.info("[maps] generating lobby world " + name + "...");
                Worlds.delete(dir);
                gm = kind == 0 ? LobbyGenerator.main(31L) : kind == 1 ? LobbyGenerator.bedwars(32L) : LobbyGenerator.skywars(33L);
            }
            org.bukkit.block.Biome biome = gm != null ? gm.biome : org.bukkit.block.Biome.PLAINS;
            if (gm == null && marker.isFile()) {
                try { biome = org.bukkit.block.Biome.valueOf(YamlConfiguration.loadConfiguration(marker).getString("biome", "PLAINS")); }
                catch (IllegalArgumentException ignored) {}
            }
            World w = Worlds.loadVoid(name, biome, 0.5, LobbyGenerator.PY + 1, 0.5);
            if (w == null) throw new IllegalStateException("could not load " + name);
            if (gm != null) {
                w.setAutoSave(true);
                gm.canvas.flush(w, log);
                w.setSpawnLocation(0, LobbyGenerator.PY + 1, 0);
                Worlds.saveFully(w, log);
                YamlConfiguration y = new YamlConfiguration();
                y.set("version", LOBBY_VERSION);
                y.set("biome", gm.biome.name());
                y.set("generated", System.currentTimeMillis());
                y.save(marker);
            }
            Worlds.applyRules(w, false);
            w.setSpawnLocation(0, LobbyGenerator.PY + 1, 0);
            for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) w.loadChunk(cx, cz, true);
            return w;
        } catch (Throwable e) {
            log.log(Level.SEVERE, "[maps] lobby " + name + " unavailable", e);
            return null;
        }
    }

    /** plugins/Hypixel2020/lobby-npcs.yml: NPC pad positions for core (x,y,z,yaw per key). */
    private void writeLobbyNpcs() {
        File f = new File(plugin.getDataFolder(), "lobby-npcs.yml");
        if (f.isFile() && YamlConfiguration.loadConfiguration(f).getInt("version", 0) >= LOBBY_VERSION) return;
        YamlConfiguration y = new YamlConfiguration();
        y.options().header("NPC pads of the generated hubs (written by the maps module). Positions are feet\n"
                + "positions (block centre); yaw faces the spawn at (0.5, 71, 0.5). Top-level bedwars/skywars are the\n"
                + "main lobby game NPCs; *_lobby sections hold the per-mode NPCs of the family lobbies.");
        y.set("version", LOBBY_VERSION);
        y.set("world", LobbyGenerator.MAIN);
        y.set("spawn", pos(new double[]{0.5, LobbyGenerator.PY + 1, 0.5, 180}));
        for (Map.Entry<String, double[]> e : LobbyGenerator.npcPads(LobbyGenerator.MAIN).entrySet()) {
            String key = e.getKey().equals("bedwars") || e.getKey().equals("skywars") ? e.getKey() : "extra." + e.getKey();
            y.set(key, pos(e.getValue()));
        }
        section(y, "bedwars_lobby", LobbyGenerator.BEDWARS);
        section(y, "skywars_lobby", LobbyGenerator.SKYWARS);
        try {
            y.save(f);
        } catch (IOException e) {
            log.warning("[maps] could not write " + f + ": " + e.getMessage());
        }
    }

    private static void section(YamlConfiguration y, String key, String world) {
        y.set(key + ".world", world);
        y.set(key + ".spawn", pos(new double[]{0.5, LobbyGenerator.PY + 1, 0.5, 180}));
        for (Map.Entry<String, double[]> e : LobbyGenerator.npcPads(world).entrySet()) y.set(key + "." + e.getKey(), pos(e.getValue()));
    }

    private static Map<String, Object> pos(double[] p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", p[0]); m.put("y", p[1]); m.put("z", p[2]); m.put("yaw", (double) (float) p[3]);
        return m;
    }

    private void writeFormatHelp() {
        File f = new File(mapsDir, "FORMAT.txt");
        if (f.isFile()) return;
        String text = "Hypixel2020 maps\r\n"
                + "================\r\n"
                + "Each map is a folder here: <folder>/world (a normal Minecraft 1.8 world folder) + <folder>/map.yml.\r\n"
                + "Generated maps are rebuilt when deleted. To import a community map, copy its world folder into\r\n"
                + "<folder>/world and write map.yml like the generated ones:\r\n\r\n"
                + "name: My Map\r\nfamily: BEDWARS            # or SKYWARS\r\nteams: [8]                 # bedwars 8 or 4, skywars 12 (solo) or 8 (teams)\r\n"
                + "build-limit: 91\r\nbiome: PLAINS\r\nbounds: {min: {x: -120, y: 0, z: -120}, max: {x: 120, y: 91, z: 120}}\r\n"
                + "points:\r\n  waiting.lobby: [{x: 0.5, y: 106, z: 0.5}]\r\n  spectator: [{x: 0.5, y: 95, z: 0.5}]\r\n  mid: [{x: 0.5, y: 69, z: 5.5}]\r\n"
                + "  team.red.spawn: [{x: -27.5, y: 66, z: -80.5, yaw: 0}]\r\n  team.red.bed: [\"-28,66,-74\", \"-28,66,-73\"]   # foot then head\r\n"
                + "  team.red.generator: [{x: -27.5, y: 66, z: -85.5}]\r\n  team.red.shop: [{x: -33.5, y: 66, z: -80.5, yaw: 90}]\r\n"
                + "  team.red.upgrades: [{x: -21.5, y: 66, z: -80.5, yaw: 270}]\r\n  team.red.base: [\"-41,53,-91\", \"-15,79,-65\"]   # min, max\r\n"
                + "  gen.diamond: [...]\r\n  gen.emerald: [...]\r\n\r\n"
                + "SkyWars keys: island.<i>.cage (feet position inside the cage), island.<i>.chests (block positions),\r\n"
                + "island.<i>.bounds (min,max), mid.chests. Team colours: red blue green yellow aqua white pink gray.\r\n"
                + "Points may also be written as nested YAML (team: {red: {spawn: [...]}}) or as \"x,y,z[,yaw]\" strings.\r\n";
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
            w.write(text);
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) {}
        }
    }

    // ============================================================================================ MapService
    @Override
    public List<MapTemplate> templates(GameFamily family) {
        List<MapTemplate> l = new ArrayList<MapTemplate>();
        for (StoredMapTemplate t : templates.values()) if (family == null || t.family == family) l.add(t);
        return Collections.unmodifiableList(l);
    }

    @Override
    public List<MapTemplate> templatesFor(GameType type) {
        List<MapTemplate> l = new ArrayList<MapTemplate>();
        if (type == null) return l;
        for (StoredMapTemplate t : templates.values())
            if (t.family == type.family && t.teamCounts.contains(type.teams)) l.add(t);
        return Collections.unmodifiableList(l);
    }

    @Override
    public MapTemplate byName(String name) {
        if (name == null) return null;
        StoredMapTemplate t = templates.get(name.toLowerCase(Locale.ROOT));
        if (t != null) return t;
        for (StoredMapTemplate s : templates.values())
            if (s.folder.getName().equalsIgnoreCase(name) || s.name.replace(" ", "").equalsIgnoreCase(name.replace(" ", "").replace("_", "")))
                return s;
        return null;
    }

    @Override
    public World mainLobby() {
        if (mainLobby != null && Bukkit.getWorld(mainLobby.getUID()) == null) mainLobby = null;
        return mainLobby;
    }

    @Override
    public World familyLobby(GameFamily family) {
        if (family == null) return mainLobby();
        World w = family == GameFamily.BEDWARS ? bedwarsLobby : skywarsLobby;
        if (w != null && Bukkit.getWorld(w.getUID()) == null) w = null;
        return w != null ? w : mainLobby();
    }

    /** Template an instance world was created from (null if not an instance). */
    public MapTemplate templateOf(World w) {
        return w == null ? null : instances.get(w.getName().toLowerCase());
    }
}
