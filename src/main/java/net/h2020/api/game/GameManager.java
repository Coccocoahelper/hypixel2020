package net.h2020.api.game;

import net.h2020.api.map.MapTemplate;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Owned by core. Matchmaking + lifecycle. queue() puts the player into a WAITING game of that type (creating one
 * if needed, from a random MapService template). Core's queue filler then trickles bots in with realistic join
 * timing until the game is full. Games call finished(game) when they are torn down.
 */
public interface GameManager {
    void registerProvider(GameProvider provider);
    Game queue(Player player, GameType type);
    /** Create a game explicitly (used by /h2020 commands and the queue). */
    Game create(GameType type, MapTemplate map);
    Game gameOf(Player player);
    Collection<Game> games();
    /** Send to the family lobby (or main lobby if family null), resetting inventory/effects/scoreboard/gamemode. */
    void sendToLobby(Player player, GameFamily family);
    void finished(Game game);
}
