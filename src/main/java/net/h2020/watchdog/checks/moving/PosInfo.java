package net.h2020.watchdog.checks.moving;

/** Server-side environment of a player bounding box at one position (computed by {@link Collisions}). */
final class PosInfo {
    double x, y, z;
    /** All chunks touched were loaded (if not, every check is lenient). */
    boolean loaded = true;
    /** A collision box (block, ghost block or boat/minecart) directly under the feet. */
    boolean ground;
    /** Ground within 0.5 blocks below (lenient helper). */
    boolean nearGround;
    /** Something directly above the head. */
    boolean headBlocked;
    /** Vanilla isInWater (1) / isInLava (2), else 0. */
    int liquid;
    /** Any liquid touching the slightly expanded box. */
    boolean liquidLoose;
    /** Vanilla isOnLadder: ladder/vine at (floor x, floor minY, floor z). */
    boolean climbable;
    /** Web inside the contracted box (Entity.checkBlockCollisions). */
    boolean web;
    /** Soul sand inside the contracted box (motion x0.4 after the move). */
    boolean soulSand;
    /** Slime block under the feet (BlockSlime bounce / no fall damage). */
    boolean onSlime;
    /** Slipperiness of the block below (vanilla: floor(minY) - 1). */
    float slip = 0.6f;
}
