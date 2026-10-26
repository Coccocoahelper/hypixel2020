package net.h2020.watchdog.checks.blockbreak;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.h2020.watchdog.framework.PlayerData;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * NCP BlockBreak.FastBreak: compares the real time between START_DESTROY_BLOCK and STOP_DESTROY_BLOCK (packet
 * arrival, immune to server lag) with the vanilla breaking time. The per-tick damage comes from NMS
 * Block.getDamage(human, world, pos), i.e. tool, Efficiency, Haste, Mining Fatigue, water and air penalties exactly as
 * the server computes them; the air penalty is waived (jump-mining is judged leniently). The 1.8 client applies
 * the first damage in the START tick, so ceil(1/damage) - 1 ticks pass before STOP. Shortfalls drain a buffer.
 */
public final class FastBreak extends Check {
    static final double BUFFER = 600;
    static final long TOLERANCE_MS = 50;

    public FastBreak(WatchdogServiceImpl service) { super(service, CheckType.BLOCKBREAK_FASTBREAK); }

    /** @return expected ms between START and STOP, or -1 for instant breaks. */
    public static long expectedMs(Player p, Block b) {
        EntityPlayer h = ((CraftPlayer) p).getHandle();
        World w = ((CraftWorld) b.getWorld()).getHandle();
        BlockPosition pos = new BlockPosition(b.getX(), b.getY(), b.getZ());
        float dmg = w.getType(pos).getBlock().getDamage(h, w, pos);
        if (!h.onGround) dmg *= 5f;
        if (dmg >= 1f) return -1;
        if (dmg <= 0f) return Long.MAX_VALUE; // unbreakable
        long ticks = (long) Math.ceil(1.0 / dmg);
        return Math.max(0, ticks - 1) * 50L;
    }

    /** @return cancel */
    public boolean check(Player p, PlayerData d, Block b, long elapsed) {
        if (exempt(p)) return false;
        long expected = expectedMs(p, b);
        if (expected < 0 || expected == Long.MAX_VALUE) return false;
        BlockBreakData bd = d.dig;
        long missing = expected - elapsed - TOLERANCE_MS;
        String det = "elapsed=" + elapsed + "ms expected=" + expected + "ms " + b.getType().name().toLowerCase();
        if (missing > 0) {
            bd.fastBreakBuffer -= missing;
            if (bd.fastBreakBuffer < 0) {
                double vl = -bd.fastBreakBuffer / 100.0;
                bd.fastBreakBuffer = 0;
                return flag(p, Math.max(1.0, vl), det);
            }
            debug(p, det + " buffer=" + (int) bd.fastBreakBuffer);
            return false;
        }
        bd.fastBreakBuffer = Math.min(BUFFER, bd.fastBreakBuffer + 50 + Math.min(200, -missing));
        reward(p, 0.97);
        if (debugging(p)) debug(p, "ok " + det);
        return false;
    }
}
