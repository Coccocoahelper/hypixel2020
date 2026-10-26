package net.h2020.core.game;

import net.h2020.api.game.GameFamily;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Callbacks from GameManager into the lobby layer (implemented by LobbyManager). */
public interface LobbyHooks {
    /** Lobby world for a family (null = main lobby), never null. */
    World lobbyWorld(GameFamily family);
    /** Where players appear in a lobby world. */
    Location spawn(World lobby);
    /** Player has been reset and teleported into a lobby: give items, sidebar, nametag, join message. */
    void entered(Player p, GameFamily family);
    /** Player is about to leave the lobby for a game (restore visibility etc). */
    void leaving(Player p);
}
