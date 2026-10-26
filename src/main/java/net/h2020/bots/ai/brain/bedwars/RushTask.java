package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * Rush an enemy base: bridge to their island, walk/dig to the bed and break it, tunnelling through the defense
 * with whatever the crosshair hits (shears for wool, pickaxe for end stone/clay, axe for wood). Afterwards hang
 * around briefly for the final kills (combat interrupts take care of the fighting).
 */
public final class RushTask extends BwTask {
    private enum Phase { TRAVEL, APPROACH, TNT, FLEE, BREAK, AFTER }

    private static final Set<Material> DEFENSE = EnumSet.of(Material.WOOL, Material.STAINED_CLAY, Material.HARD_CLAY,
            Material.ENDER_STONE, Material.WOOD, Material.STAINED_GLASS, Material.GLASS, Material.LADDER, Material.OBSIDIAN);

    public final TeamColor target;
    private Phase phase = Phase.TRAVEL;
    private long phaseStart;
    private final BlockBreaker breaker;
    private boolean calledOut;
    private final int afterLen;
    private final BlockPlacer placer;
    private boolean tntUsed;
    private Location fleeFrom;

    public RushTask(BedwarsBrain brain, TeamColor target) {
        super(brain);
        this.target = target;
        this.breaker = new BlockBreaker(ctx);
        this.placer = new BlockPlacer(ctx);
        this.phaseStart = ctx.tick;
        this.afterLen = AIMath.range(ctx.rng, 60, 200);
    }

    @Override public String name() { return "rush-" + target.name().toLowerCase(); }

    private void phase(Phase p) { phase = p; phaseStart = ctx.tick; }

    @Override
    public boolean tick() {
        Location bed = view.bedLocation(target);
        if (bed == null) return false;
        Location bedC = bed.clone().add(0.5, 0.3, 0.5);
        long inPhase = ctx.tick - phaseStart;
        boolean alive = view.bedAlive(target);
        if (!alive && phase != Phase.AFTER) {
            phase(Phase.AFTER);
            ctx.chat.callout(ctx.persona.toxic ? "ez bed" : "bed broken", 0.25);
        }
        double eyeDist = ctx.eye().distance(bedC);
        switch (phase) {
            case TRAVEL: {
                if (!calledOut && brain.teamMode()) { calledOut = true; ctx.chat.callout("rushing " + target.displayName.toLowerCase(), 0.4); }
                if (eyeDist < 6) { phase(Phase.APPROACH); return true; }
                if (brain.blocks() < 4 && ctx.nav.path() == null && inPhase > 40) return false;
                NavOptions o = NavOptions.bridging(brain.blocks());
                o.maxNodes = 12000;
                o.breakBlocks = true;
                o.breakable = DEFENSE;
                o.extraVoidAversion = brain.finalLife() ? 0.8 : 0;
                o.avoidEnemies = false;
                ctx.nav.goTo(bedC, 3.5, o);
                ctx.nav.tick();
                if (ctx.nav.failed() || inPhase > 20 * 120) return false;
                return true;
            }
            case APPROACH: {
                if (eyeDist < 4.1) {
                    ctx.nav.stop();
                    int[] cell = tntCell(bed);
                    if (cell != null) { placer.start(cell[0], cell[1], cell[2], Inv.TNT); phase(Phase.TNT); return true; }
                    startBreak(bed); phase(Phase.BREAK); return true;
                }
                NavOptions o = NavOptions.walk();
                o.breakBlocks = true;
                o.breakable = DEFENSE;
                o.parkourGap = 0;
                ctx.nav.goTo(bedC, 1.8, o);
                ctx.nav.tick();
                if (ctx.nav.failed() || inPhase > 20 * 30) return false;
                return true;
            }
            case TNT: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                tntUsed = true;
                if (r == BlockPlacer.Result.DONE) { fleeFrom = bedC; phase(Phase.FLEE); }
                else phase(Phase.APPROACH);
                return true;
            }
            case FLEE: {
                // step away from the lit TNT (Hypixel TNT auto-ignites, ~2.6s fuse), then go back in
                if (inPhase > 62) { phase(Phase.APPROACH); return true; }
                Location me = ctx.pos();
                double dx = me.getX() - fleeFrom.getX(), dz = me.getZ() - fleeFrom.getZ();
                double l = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                if (l < 5.5) {
                    Location away = me.clone().add(dx / l * 3, 0, dz / l * 3);
                    ctx.nav.goTo(away, 1.0, NavOptions.walk());
                    ctx.nav.tick();
                } else ctx.nav.stop();
                return true;
            }
            case BREAK: {
                if (eyeDist > 4.6) { phase(Phase.APPROACH); return true; }
                BlockBreaker.Result r = breaker.tick();
                if (r != BlockBreaker.Result.RUNNING) {
                    if (!view.bedAlive(target)) { phase(Phase.AFTER); return true; }
                    startBreak(bed);
                }
                if (inPhase > 20 * 45) return false;
                return true;
            }
            case AFTER:
            default:
                ctx.nav.stop();
                return inPhase < afterLen && age() < 20 * 200;
        }
    }

    /** An air cell next to hard defense blocks (end stone / obsidian / wood) where TNT would open the bed up. */
    private int[] tntCell(Location foot) {
        if (tntUsed || !ctx.persona.tryhard || !Inv.has(ctx.player, Inv.TNT)) return null;
        WorldView wv = ctx.world();
        int bx = foot.getBlockX(), by = foot.getBlockY(), bz = foot.getBlockZ();
        boolean hard = false;
        for (int dx = -2; dx <= 2 && !hard; dx++) for (int dy = 0; dy <= 2 && !hard; dy++) for (int dz = -2; dz <= 2 && !hard; dz++) {
            Material m = wv.type(bx + dx, by + dy, bz + dz);
            hard = m == Material.ENDER_STONE || m == Material.OBSIDIAN || m == Material.WOOD;
        }
        if (!hard) return null;
        Location eye = ctx.eye();
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (wv.shape(x, y, z) != Shape.OPEN) continue;
            if (BlockPlacer.findSupport(wv, x, y, z, eye, 4.2) == null) continue;
            if (!BlockPlacer.cellFree(new Location(eye.getWorld(), x, y, z))) continue;
            double d = (x + 0.5 - bx - 0.5) * (x - bx) + (y - by) * (y - by) + (z - bz) * (z - bz);
            if (d < bd) { bd = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    private void startBreak(Location foot) {
        int[] head = BedDefense.head(foot.getWorld(), foot);
        // break whichever half is closer
        Location eye = ctx.eye();
        double df = eye.distanceSquared(foot.clone().add(0.5, 0.3, 0.5));
        double dh = eye.distanceSquared(new Location(foot.getWorld(), head[0] + 0.5, head[1] + 0.3, head[2] + 0.5));
        if (dh < df) breaker.start(head[0], head[1], head[2], true);
        else breaker.start(foot.getBlockX(), foot.getBlockY(), foot.getBlockZ(), true);
    }

    @Override
    public void abort() {
        super.abort();
        breaker.cancel();
        placer.cancel();
    }
}
