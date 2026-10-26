package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cover the bed: layer 1 wool, then layer 2 end stone (or more wool for dedicated defenders). Walks to stand spots
 * around the bed from which the remaining cells are in reach, and places them one by one with BlockPlacer
 * (visible support face, precise aim, right-click on the real ray hit). Also used to patch holes later.
 */
public final class DefendTask extends BwTask {
    private final BlockPlacer placer;
    private BedDefense def;
    private final Set<Long> skipped = new HashSet<>();
    private Location stand;
    private int navFails;
    private int placedCount;
    private final boolean secondLayer;
    private int idleTicks;
    private final Set<Long> hiddenHere = new HashSet<>();
    private int placeFails;

    public DefendTask(BedwarsBrain brain, boolean secondLayer) {
        super(brain);
        this.placer = new BlockPlacer(ctx);
        this.secondLayer = secondLayer;
    }

    @Override public String name() { return "defend"; }

    public int placed() { return placedCount; }

    @Override
    public boolean tick() {
        if (brain.color() == null || !view.bedAlive(brain.color())) return false;
        if (def == null) def = BedDefense.of(view.bedLocation(brain.color()));
        if (age() > 20 * 40) return false;
        WorldView wv = ctx.world();

        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            if (r == BlockPlacer.Result.DONE) { placedCount++; idleTicks = AIMath.range(ctx.rng, 0, 3); }
            else if (r == BlockPlacer.Result.FAILED) {
                long k = AIMath.pack(placer.targetX(), placer.targetY(), placer.targetZ());
                // hidden from this spot only (wool we just placed is in the way): try it again from the next stand
                if (++placeFails > 12 || !hiddenHere.add(k)) skipped.add(k);
            }
            return true;
        }
        if (idleTicks > 0) { idleTicks--; return true; }

        // choose layer + material
        List<int[]> todo = def.open(def.layer1, wv);
        Inv.Pred mat = Inv.type(Material.WOOL);
        removeSkipped(todo);
        if (todo.isEmpty()) {
            if (!secondLayer) return false;
            todo = def.open(def.layer2, wv);
            removeSkipped(todo);
            if (todo.isEmpty()) return false;
            if (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE))) mat = Inv.type(Material.ENDER_STONE);
            else if (Inv.has(ctx.player, Inv.type(Material.STAINED_CLAY))) mat = Inv.type(Material.STAINED_CLAY);
            else if (Inv.has(ctx.player, Inv.type(Material.WOOD))) mat = Inv.type(Material.WOOD);
            else if (Inv.count(ctx.player, Inv.type(Material.WOOL)) < 8) return false;
        }
        if (!Inv.has(ctx.player, mat)) {
            if (Inv.has(ctx.player, Inv.BLOCK)) mat = Inv.BLOCK; else return false;
        }

        Location eye = ctx.eye();
        // a reachable cell from where we are?
        for (int[] c : todo) {
            if (hiddenHere.contains(AIMath.pack(c[0], c[1], c[2]))) continue;
            if (BlockPlacer.findSupport(wv, c[0], c[1], c[2], eye, 4.3, true) == null) continue;
            if (!BlockPlacer.cellFree(new Location(eye.getWorld(), c[0], c[1], c[2]))) continue;
            ctx.nav.stop();
            placer.setNeedSight(true);
            placer.start(c[0], c[1], c[2], mat);
            return true;
        }
        // move to a better spot
        if (stand == null || ctx.pos().distance(stand) < 0.6 || ctx.nav.failed()) {
            if (ctx.nav.failed()) navFails++;
            if (navFails > 3) return false;
            Location prev = stand;
            hiddenHere.clear();
            stand = standSpot(todo, wv);
            if (stand == null || (prev != null && prev.distanceSquared(stand) < 0.1 && ctx.pos().distance(stand) < 0.8)) {
                // stuck at the best spot with nothing placeable: give the rest up
                for (int[] c : todo) skipped.add(AIMath.pack(c[0], c[1], c[2]));
                return !todo.isEmpty();
            }
        }
        NavOptions o = NavOptions.walk();
        o.forbidden = def.allCells;
        o.sprint = ctx.pos().distance(stand) > 3;
        ctx.nav.goTo(stand, 0.5, o);
        ctx.nav.tick();
        return true;
    }

    private void removeSkipped(List<int[]> l) {
        for (int i = l.size() - 1; i >= 0; i--) {
            int[] c = l.get(i);
            if (skipped.contains(AIMath.pack(c[0], c[1], c[2]))) l.remove(i);
        }
    }

    private Location standSpot(List<int[]> todo, WorldView wv) {
        double[] ctr = def.center();
        int cx = (int) Math.floor(ctr[0]), cz = (int) Math.floor(ctr[2]), by = def.bed[0][1];
        Location me = ctx.pos();
        Location best = null;
        double bs = 0.5;
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) for (int y = by - 1; y <= by + 1; y++) {
            if (def.allCells.contains(AIMath.pack(x, y, z))) continue;
            if (!wv.standable(x, y, z)) continue;
            double s = wv.stand(x, y, z);
            double hd = Math.sqrt(AIMath.hDistSq(x + 0.5, z + 0.5, ctr[0], ctr[2]));
            if (hd < 1.6 || hd > 4.6) continue;
            // beside the void is fine for a moment (players defend from the edge), just not preferred
            int vs = wv.voidSides(x, y, z);
            if (vs > 1) continue;
            Location eye = new Location(me.getWorld(), x + 0.5, s + 1.62, z + 0.5);
            int reach = 0;
            for (int[] c : todo) {
                double dx = c[0] + 0.5 - eye.getX(), dy = c[1] + 0.5 - eye.getY(), dz = c[2] + 0.5 - eye.getZ();
                if (dx * dx + dy * dy + dz * dz > 4.6 * 4.6) continue;
                // count only cells whose support face is actually visible from there
                if (BlockPlacer.findSupport(wv, c[0], c[1], c[2], eye, 4.3, true) != null) reach++;
            }
            double score = reach - 0.2 * Math.sqrt(AIMath.hDistSq(x + 0.5, z + 0.5, me.getX(), me.getZ())) - (vs > 0 ? 1.2 : 0);
            if (score > bs) { bs = score; best = new Location(me.getWorld(), x + 0.5, s, z + 0.5); }
        }
        return best;
    }

    @Override
    public void abort() {
        super.abort();
        placer.cancel();
    }
}
