package net.h2020.watchdog.checks.moving;

import net.h2020.api.watchdog.CheckType;

/**
 * NCP Passable: a move that enters or crosses a block collision box (phase / noclip / vclip). The move is swept in
 * vanilla axis order so legit stepping, sliding along walls and fast falls never intersect anything; boxes the
 * player already overlapped (stuck in a door that closed, a block appearing inside them) are ignored, and cells the
 * client has not been told about yet (ghost air) are skipped. Opened doors, gates and trapdoors are handled by
 * their real state-dependent collision boxes. Sets back to the position before the move.
 */
final class Passable {
    private Passable() {}

    static void check(MovingEngine eng, MoveContext c) {
        if (!c.hasPos || c.spectator || c.handle.noclip) return;
        if (c.dx == 0 && c.dy == 0 && c.dz == 0) return;
        if (c.data.leniencyTicks > 0 || !c.from.loaded || !c.to.loaded) return;
        if (c.legacyHover && c.dx * c.dx + c.dz * c.dz < 1.0 && Math.abs(c.dy) <= 0.13) return; // 2020 hover gap
        if (!eng.active(c.player, CheckType.MOVING_PASSABLE)) return;
        String axis = Collisions.passableHit(c.world, c.handle, c.fromX, c.fromY, c.fromZ, c.toX, c.toY, c.toZ,
                c.data.ghosts, c.entry.time);
        if (axis == null) return;
        c.violated = true;
        double dist = Math.sqrt(c.dx * c.dx + c.dy * c.dy + c.dz * c.dz);
        boolean cancel = eng.flag(c.player, CheckType.MOVING_PASSABLE, 1.0 + Math.min(4.0, dist),
                String.format("passable: moved through a block on the %s axis (%.3f blocks, %d %d %d)", axis, dist,
                        Collisions.floor(c.toX), Collisions.floor(c.toY), Collisions.floor(c.toZ)));
        if (cancel) c.requestSetback(c.fromLocation());
    }
}
