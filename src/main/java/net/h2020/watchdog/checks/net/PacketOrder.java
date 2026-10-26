package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.ItemSword;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;

/**
 * Packet order within one client tick (the actions of a tick precede that tick's flying packet).
 * Vanilla 1.8.9 Minecraft.runTick():
 *   if (player.isUsingItem()) { release if key up (C07 RELEASE_USE_ITEM); swallow attack/use presses }
 *   else { clickMouse() -> C0A + C02 ATTACK ...; rightClickMouse() -> C08 }
 * hence
 *   - C02 ATTACK while a sword block (C08 face 255) is active and not yet released is impossible ("autoblock");
 *   - C07 RELEASE followed by C02 ATTACK in the same tick is impossible (unblock-hit killaura);
 *   - EntityPlayerSP.onUpdateWalkingPlayer sends at most one sprint and one sneak state change per tick.
 * Block-hitting (C02 then C08 in one tick) is legitimate and not flagged.
 */
final class PacketOrder {
    static final double SPAM_WINDOW_MS = 1000;
    static final int SPAM_TICKS = 3;
    static final double VIOLATION_WINDOW_MS = 5000;
    static final int VIOLATIONS_TO_FLAG = 2;

    private PacketOrder() {}

    private static void checkReset(NetData d) {
        if (d.resetRequested) {
            d.resetRequested = false;
            d.resetClientState();
        }
    }

    /** End of a client tick. */
    static void onFlying(NetChecks nc, Player p, NetData d, double now) {
        checkReset(d);
        if (d.sprintToggles > 1 || d.sneakToggles > 1) {
            d.orderSpam.addLast(now);
            trim(d.orderSpam, now, SPAM_WINDOW_MS);
            if (d.orderSpam.size() >= SPAM_TICKS) {
                d.orderSpam.clear();
                nc.flag(p, CheckType.NET_PACKETORDER, 1.0,
                        "packetorder: entity action spam (sprint " + d.sprintToggles + ", sneak " + d.sneakToggles + " in one tick)", false);
            }
        }
        d.sprintToggles = 0;
        d.sneakToggles = 0;
        d.releasedThisTick = false;
    }

    static void onEntityAction(NetData d, String action) {
        checkReset(d);
        if ("START_SPRINTING".equals(action) || "STOP_SPRINTING".equals(action)) d.sprintToggles++;
        else if ("START_SNEAKING".equals(action) || "STOP_SNEAKING".equals(action)) d.sneakToggles++;
    }

    static void onBlockPlace(NetData d, int face, ItemStack item) {
        checkReset(d);
        if (face == 255 && item != null && item.getItem() instanceof ItemSword) d.blocking = true;
    }

    static void onReleaseUseItem(NetData d) {
        checkReset(d);
        d.blocking = false;
        d.releasedThisTick = true;
    }

    static void onHeldItemChange(NetData d) {
        checkReset(d);
        d.blocking = false;
    }

    static void onAttack(NetChecks nc, Player p, NetData d, double now) {
        checkReset(d);
        String what = null;
        if (d.blocking) what = "attack while blocking (no C07 release)";
        else if (d.releasedThisTick) what = "release + attack in one tick";
        if (what == null) return;
        d.orderViolations.addLast(now);
        trim(d.orderViolations, now, VIOLATION_WINDOW_MS);
        if (d.orderViolations.size() >= VIOLATIONS_TO_FLAG) {
            nc.flag(p, CheckType.NET_PACKETORDER, 1.0, "packetorder: " + what, false);
        }
    }

    private static void trim(ArrayDeque<Double> q, double now, double window) {
        while (!q.isEmpty() && now - q.peekFirst() > window) q.removeFirst();
    }
}
