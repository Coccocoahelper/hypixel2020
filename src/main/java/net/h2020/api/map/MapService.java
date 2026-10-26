package net.h2020.api.map;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import org.bukkit.World;

import java.util.List;

/** Owned by maps. Registered in MapsModule.enable() after templates are generated/loaded. */
public interface MapService {
    List<MapTemplate> templates(GameFamily family);
    /** Templates usable for this type (team count matches). */
    List<MapTemplate> templatesFor(GameType type);
    MapTemplate byName(String name);
    /** The main lobby world and the per-family lobby worlds (generated Hypixel-style hubs). */
    World mainLobby();
    World familyLobby(GameFamily family);
}
