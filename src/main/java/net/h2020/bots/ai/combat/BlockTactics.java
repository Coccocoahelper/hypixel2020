package net.h2020.bots.ai.combat;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Pillarer;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Blocks as a fight tool: towering up a few blocks when low and chased (then eating / shooting down from the top),
 * walling off a 1-wide bridge behind you when fleeing over it (two blocks: they have to stop and break it), and
 * cutting the bridge behind you with shears or a pickaxe when the chaser is a few blocks back.
 */
final class BlockTactics {
    private final BotContext ctx;
    private final Style style;
    private final Pillarer pillar;
    private final BlockPlacer placer;
    private final BlockBreaker breaker;
    private int mode; // 0 none, 1 pillar, 2 wall, 3 cut
    private int[][] cells;
    private int cellIdx;
    private long started;
    private long lastPillar = -10000, lastWall = -10000, lastCut = -10000;
    /** After towering: hold the high ground instead of chasing down. */
    long holdHighUntil = -1;
    double highY;

    BlockTactics(BotContext ctx, Style style) {
        this.ctx = ctx;
        this.style = style;
        this.pillar = new Pillarer(ctx);
        this.placer = new BlockPlacer(ctx);
        this.breaker = new BlockBreaker(ctx);
    }

    boolean busy() { return mode != 0; }

    void cancel() {
        if (mode == 1) pillar.cancel();
        if (mode == 2) placer.cancel();
        if (mode == 3) breaker.cancel();
        mode = 0;
    }

    boolean holdingHigh() { return ctx.tick < holdHighUntil && ctx.pos().getY() > highY - 0.6; }

    /** Keep running the current tactic. @return true while it drives the inputs. */
    boolean tick() {
        if (ctx.tick - started > 120) { cancel(); return false; }
        switch (mode) {
            case 1: {
                Pillarer.Result r = pillar.tick();
                if (r == Pillarer.Result.RUNNING) return true;
                mode = 0;
                if (r == Pillarer.Result.DONE) {
                    CombatStats.inc("block.pillar.ok");
                    highY = ctx.pos().getY();
                    holdHighUntil = ctx.tick + AIMath.range(ctx.rng, 80, 200);
                }
                return false;
            }
            case 2: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                if (r == BlockPlacer.Result.DONE && cellIdx == 0) CombatStats.inc("block.wall");
                if (++cellIdx < cells.length && r == BlockPlacer.Result.DONE) {
                    placer.setNeedSight(true);
                    placer.start(cells[cellIdx][0], cells[cellIdx][1], cells[cellIdx][2], Inv.BLOCK);
                    return true;
                }
                mode = 0;
                return false;
            }
            case 3: {
                BlockBreaker.Result r = breaker.tick();
                if (r == BlockBreaker.Result.RUNNING) return true;
                if (r == BlockBreaker.Result.DONE) CombatStats.inc("block.cut");
                mode = 0;
                return false;
            }
            default:
                return false;
        }
    }

    /**
     * Should we use blocks right now? Starts a tactic and returns true when it does.
     * @param losing hp low and the opponent healthier
     */
    boolean consider(Player target, double d, boolean losing) {
        if (mode != 0) return tick();
        if (ctx.nav.doingAction() || !ctx.c.onGround()) return false;
        Location me = ctx.pos();
        Location tl = target.getLocation();
        WorldView wv = ctx.world();
        int x = floor(me.getX()), y = floor(me.getY() + 0.01), z = floor(me.getZ());
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        boolean hotBlocks = Inv.findHotbar(ctx.player, Inv.BLOCK) >= 0;
        double hp = ctx.health();
        boolean onBridge = wv.voidSides(x, y, z) >= 2;

        // tower up out of melee when low and chased (then the gapple / bow comes out at the top)
        if (style.blockIn && losing && hp <= 8 && d > 2.2 && d < 7 && blocks >= 4 && hotBlocks && ctx.tick - lastPillar > 600
                && !onBridge && Math.abs(tl.getY() - me.getY()) < 1.5) {
            boolean room = true;
            for (int i = 2; i <= 5; i++) if (!wv.passable(x, y + i, z)) { room = false; break; }
            if (room && ctx.rng.nextDouble() < 0.25) {
                lastPillar = ctx.tick;
                started = ctx.tick;
                pillar.start(x, y, z, AIMath.range(ctx.rng, 3, 4));
                mode = 1;
                CombatStats.inc("block.pillar");
                ctx.nav.stop();
                return tick();
            }
        }
        if (!onBridge || d < 2.2 || d > 8) return false;
        // they are behind us on the same bridge?
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        int sx, sz;
        if (Math.abs(dx) > Math.abs(dz) * 2) { sx = (int) Math.signum(dx); sz = 0; }
        else if (Math.abs(dz) > Math.abs(dx) * 2) { sx = 0; sz = (int) Math.signum(dz); }
        else return false;
        if (Math.abs(tl.getY() - me.getY()) > 1.2) return false;
        int bx = x + sx, bz = z + sz;
        // wall: two blocks in the bridge cell right behind us
        if (style.bridgeBlock && hotBlocks && blocks >= 2 && ctx.tick - lastWall > 200 && d > 2.8
                && Shape.floor(wv.shape(bx, y - 1, bz)) && wv.passable(bx, y, bz) && wv.passable(bx, y + 1, bz)
                && ctx.rng.nextDouble() < 0.2) {
            lastWall = ctx.tick;
            started = ctx.tick;
            cells = new int[][]{{bx, y, bz}, {bx, y + 1, bz}};
            cellIdx = 0;
            placer.setNeedSight(true);
            placer.start(bx, y, bz, Inv.BLOCK);
            mode = 2;
            ctx.nav.stop();
            return tick();
        }
        // cut: break the bridge block behind us (fast with shears on wool / a pickaxe on clay and stone)
        int cx = x + sx * 2, cz = z + sz * 2;
        Material m = wv.type(cx, y - 1, cz);
        if (style.bridgeBlock && d > 3.5 && ctx.tick - lastCut > 200 && Shape.floor(wv.shape(cx, y - 1, cz))
                && fastToBreak(m) && ctx.rng.nextDouble() < 0.15) {
            lastCut = ctx.tick;
            started = ctx.tick;
            breaker.start(cx, y - 1, cz, false);
            mode = 3;
            ctx.nav.stop();
            return tick();
        }
        return false;
    }

    private boolean fastToBreak(Material m) {
        if (m == Material.WOOL) return Inv.findHotbar(ctx.player, Inv.SHEARS) >= 0;
        if (m == Material.STAINED_CLAY || m == Material.HARD_CLAY || m == Material.SANDSTONE || m == Material.COBBLESTONE || m == Material.STONE)
            return Inv.findHotbar(ctx.player, Inv.PICKAXE) >= 0;
        return m == Material.WOOD && Inv.findHotbar(ctx.player, Inv.AXE) >= 0;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
