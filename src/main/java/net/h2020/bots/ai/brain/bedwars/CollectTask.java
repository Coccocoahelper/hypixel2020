package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;

/**
 * Stand on a generator (team forge, diamond or emerald) until enough has been picked up. Items are collected by
 * walking over them; the bot shuffles around on the generator like players do while waiting.
 */
public final class CollectTask extends BwTask {
    public enum Kind { IRON, DIAMOND, EMERALD }

    private final Location gen;
    private final Kind kind;
    private final int target;
    private final long maxTicks;
    private long nextShuffle;
    private Location spot;
    private long arrivedTick = -1;

    public CollectTask(BedwarsBrain brain, Location gen, Kind kind, int target, long maxTicks) {
        super(brain);
        this.gen = gen;
        this.kind = kind;
        this.target = target;
        this.maxTicks = maxTicks;
        this.spot = gen;
    }

    @Override public String name() { return "collect-" + kind.name().toLowerCase(); }

    private int have() {
        switch (kind) {
            case DIAMOND: return ShopPlanner.diamonds(ctx.player);
            case EMERALD: return ShopPlanner.emeralds(ctx.player);
            default: return ShopPlanner.iron(ctx.player);
        }
    }

    @Override
    public boolean tick() {
        if (gen == null) return false;
        // the wait budget starts on the generator: the walk/bridge there has its own (generous) cap
        boolean timeUp = arrivedTick < 0 ? age() > 20 * 75 : ctx.tick - arrivedTick > maxTicks;
        if (have() >= target || timeUp) { ctx.nav.stop(); return false; }
        double d = ctx.pos().distance(spot);
        boolean far = ctx.pos().distance(gen) > 4;
        if (far) {
            NavOptions o = kind == Kind.IRON ? NavOptions.walk() : NavOptions.bridging(brain.blocks());
            ctx.nav.goTo(gen, 1.0, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) return false;
            return true;
        }
        if (arrivedTick < 0) arrivedTick = ctx.tick;
        if (ctx.tick >= nextShuffle) {
            spot = gen.clone().add((ctx.rng.nextDouble() - 0.5) * 1.4, 0, (ctx.rng.nextDouble() - 0.5) * 1.4);
            nextShuffle = ctx.tick + AIMath.range(ctx.rng, 30, 110);
        }
        ctx.nav.stop();
        if (d > 0.45) {
            // small shuffle on the generator: just tap the keys toward the spot
            ctx.motor.moveToward(spot.getX() - ctx.pos().getX(), spot.getZ() - ctx.pos().getZ());
        }
        return true;
    }
}
