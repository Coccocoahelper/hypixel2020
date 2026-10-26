package net.h2020.watchdog.checks.moving;

import net.h2020.watchdog.checks.net.PacketClock;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.GenericAttributes;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Evaluates queued client ticks on the main thread, in order, after the server processed them. Each tick runs
 * Velocity -> SurvivalFly/CreativeFly -> NoFall -> Passable and then commits the new position and physics chain.
 * The first set-back request ends the batch (the remaining ticks precede the teleport confirmation anyway).
 */
final class MoveProcessor {
    private static final double SQRT2 = Math.sqrt(2.0);

    private final MovingEngine eng;

    MoveProcessor(MovingEngine eng) { this.eng = eng; }

    /** @return the set-back location to apply, or null. */
    Location process(Player p, MovingData d, List<FlyingEntry> batch, boolean liveLast) {
        if (batch.isEmpty()) return null;
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        WorldServer w = ((CraftWorld) p.getWorld()).getHandle();
        long now = System.currentTimeMillis();
        for (int i = 0; i < batch.size(); i++) {
            FlyingEntry e = batch.get(i);
            boolean live = liveLast && i == batch.size() - 1;
            Location sb;
            try {
                sb = processEntry(p, h, w, d, e, live, now);
            } catch (Throwable t) {
                eng.logError("moving check failed for " + p.getName(), t);
                resync(p, h, d, 3);
                return null;
            }
            if (sb != null) return sb;
        }
        return null;
    }

    void resync(Player p, EntityPlayer h, MovingData d, int lenient) {
        d.resetPhysics(p.getWorld().getName(), h.locX, h.locY, h.locZ);
        d.awaitTeleport = false;
        d.leniencyTicks = Math.max(d.leniencyTicks, lenient);
    }

    private Location processEntry(Player p, EntityPlayer h, WorldServer w, MovingData d, FlyingEntry e, boolean live, long now) {
        if (e.ignored) return null;
        String worldName = p.getWorld().getName();
        if (e.confirm) {
            d.resetPhysics(worldName, e.x, e.y, e.z);
            d.awaitTeleport = false;
            // The client's first ticks after a teleport may be merged/unreported (motion was zeroed, moves < 0.03),
            // so skip the motion prediction briefly instead of setting back again (would loop forever).
            d.leniencyTicks = Math.max(d.leniencyTicks, 3);
            return null;
        }
        int ping = Math.max(0, Math.min(1000, h.ping));
        if (d.awaitTeleport) {
            if (now - d.awaitSince > Math.min(8000L, 3000L + 3L * ping)) resync(p, h, d, 3);
            else return null;
        }
        if (!d.hasPos || !worldName.equals(d.world)) resync(p, h, d, 3);
        if (p.isDead() || h.dead || p.isInsideVehicle() || p.isSleeping()) {
            resync(p, h, d, 5);
            return null;
        }

        MoveContext c = new MoveContext();
        c.player = p;
        c.handle = h;
        c.world = w;
        c.data = d;
        c.entry = e;
        c.live = live;
        c.now = now;
        c.ping = ping;
        c.fromX = d.x; c.fromY = d.y; c.fromZ = d.z;
        c.hasPos = e.hasPos;
        c.toX = e.hasPos ? e.x : d.x;
        c.toY = e.hasPos ? e.y : d.y;
        c.toZ = e.hasPos ? e.z : d.z;
        c.dx = c.toX - c.fromX;
        c.dy = c.toY - c.fromY;
        c.dz = c.toZ - c.fromZ;
        c.hDist = Math.sqrt(c.dx * c.dx + c.dz * c.dz);

        long tick = PacketClock.serverTicks();
        PosInfo last = d.lastInfo;
        if (last != null && d.lastInfoTick == tick && last.x == c.fromX && last.y == c.fromY && last.z == c.fromZ) {
            c.from = last;
        } else {
            c.from = Collisions.info(w, h, c.fromX, c.fromY, c.fromZ, d.ghosts, e.time);
        }
        // The client landed less than 0.03 below its last reported position and so never reported it (step-up jump
        // peaking at +1.0156): it stands on that ground, so judge the move from it as a ground move.
        if (!c.from.ground && d.unreported && d.prevClientGround
                && Collisions.groundWithin(w, h, c.fromX, c.fromY, c.fromZ, SurvivalFly.UNREPORTED, d.ghosts, e.time)) {
            c.from.ground = true;
            c.from.nearGround = true;
        }
        c.to = e.hasPos ? Collisions.info(w, h, c.toX, c.toY, c.toZ, d.ghosts, e.time) : c.from;

        // ------------------------------------------------------------------ modifiers
        GameMode gm = p.getGameMode();
        c.spectator = gm == GameMode.SPECTATOR;
        c.creativeLike = c.spectator || gm == GameMode.CREATIVE;
        c.flyingMode = c.creativeLike || h.abilities.isFlying || now < d.flyGraceUntil;
        c.sprinting = h.isSprinting() || d.sprintPrev;
        boolean sneakingNow = p.isSneaking();
        boolean blockingNow = p.isBlocking();
        c.sneaking = sneakingNow && d.sneakTicks >= 2;
        c.blocking = blockingNow && d.blockTicks >= 3;
        // Sneaking/using an item stops sprinting client-side in the same tick: no sprint grace then.
        if (c.sneaking || c.blocking) c.sprinting = h.isSprinting();
        double inputFactor = (c.sneaking ? 0.3 : 1.0) * (c.blocking ? 0.2 : 1.0);
        c.inputMag = Math.min(1.0, 0.98 * SQRT2 * inputFactor);
        double base = d.walkBase;
        try {
            base = Math.max(base, h.getAttributeInstance(GenericAttributes.MOVEMENT_SPEED).b());
        } catch (Throwable ignored) {
            // attribute map not ready
        }
        double speed = base * (d.speedAmp >= 0 ? 1.0 + 0.2 * (d.speedAmp + 1) : 1.0);
        if (c.sprinting) speed *= 1.3;
        c.moveSpeed = speed;
        c.jumpAmp = d.jumpAmp;
        c.jumpMotion = 0.42 + (d.jumpAmp >= 0 ? 0.1 * (d.jumpAmp + 1) : 0.0);
        c.push = push(p, d, tick);
        c.depthStrider = depthStrider(p);
        c.attacked = d.attackedIn(e.seq);
        c.velocities = d.velocity.usable(e.seq, 20L + 2L * (ping / 50));

        // ------------------------------------------------------------------ checks
        c.legacyHover = eng.config.legacyHoverGap && legacyHoverPattern(c, d);
        VelocityCheck.check(eng, c);
        if (c.flyingMode) CreativeFly.check(eng, c);
        else SurvivalFly.check(eng, c);
        NoFall.check(eng, c);
        if (!c.spectator) Passable.check(eng, c);

        // ------------------------------------------------------------------ commit
        if (c.hasPos) {
            d.x = c.toX; d.y = c.toY; d.z = c.toZ;
            d.lastHDist = c.hDist;
            d.lastDy = c.dy;
            d.unreported = false;
            if (d.leniencyTicks > 0) d.leniencyTicks--;
        } else {
            d.unreported = true;
        }
        d.lastInfo = c.to;
        d.lastInfoTick = tick;
        d.prevClientGround = e.ground;
        d.sprintPrev = h.isSprinting();
        d.sneakTicks = sneakingNow ? Math.min(100, d.sneakTicks + 1) : 0;
        d.blockTicks = blockingNow ? Math.min(100, d.blockTicks + 1) : 0;
        if (d.debug && c.hasPos) eng.debugMove(p, c);

        if (c.setback != null) {
            Location sb = c.setback.clone();
            if (sb.getWorld() == null || !sb.getWorld().equals(p.getWorld())) sb = c.fromLocation();
            sb.setYaw(e.hasLook ? e.yaw : p.getLocation().getYaw());
            sb.setPitch(e.hasLook ? e.pitch : p.getLocation().getPitch());
            eng.prepareSetback(d, sb, now);
            return sb;
        }
        if (c.hasPos && !c.violated && (c.flyingMode || c.to.ground || c.to.liquid != 0 || c.to.climbable)) {
            d.setBack = new Location(p.getWorld(), c.toX, c.toY, c.toZ);
        }
        return null;
    }

    /**
     * The 2020 hover fly: Y alternates around a fixed height in steps of 1/16 (+-0.0625 entering, then +-0.125),
     * never claiming ground. Matches only that exact rhythm so ordinary play is still fully checked.
     */
    private static boolean legacyHoverPattern(MoveContext c, MovingData d) {
        if (!c.hasPos || c.entry.ground || c.from.liquid != 0 || c.from.climbable) return false;
        double ady = Math.abs(c.dy);
        boolean step = Math.abs(ady - 0.125) < 0.004 || Math.abs(ady - 0.0625) < 0.004;
        if (!step) return false;
        if (Math.abs(d.lastDy) < 1.0E-4) return true;                  // first oscillation after lift-off
        return Math.signum(c.dy) != Math.signum(d.lastDy) && Math.abs(c.dy + d.lastDy) < 0.07;
    }

    /** Client-side entity pushing (EntityLivingBase.collideWithNearbyEntities): up to ~0.07 per entity. */
    private static double push(Player p, MovingData d, long tick) {
        if (d.pushTick == tick) return d.pushValue;
        int n = 0;
        for (Entity o : p.getNearbyEntities(0.9, 0.6, 0.9)) {
            if (o instanceof LivingEntity && !(o instanceof ArmorStand)) {
                if (o instanceof Player && ((Player) o).getGameMode() == GameMode.SPECTATOR) continue;
                if (++n >= 3) break;
            }
        }
        d.pushTick = tick;
        d.pushValue = n * 0.075;
        return d.pushValue;
    }

    private static int depthStrider(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots == null) return 0;
        try {
            return boots.getEnchantmentLevel(Enchantment.DEPTH_STRIDER);
        } catch (Throwable t) {
            return 0;
        }
    }
}
