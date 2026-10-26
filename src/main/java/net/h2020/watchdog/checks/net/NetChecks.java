package net.h2020.watchdog.checks.net;

import net.h2020.api.watchdog.CheckType;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.PacketPlayInEntityAction;
import net.minecraft.server.v1_8_R3.PacketPlayInHeldItemSlot;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayInWindowClick;
import net.minecraft.server.v1_8_R3.Packet;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * Facade of the NET check group (NCP net.*). Driven by the moving module's PacketHub listener, on the player's
 * packet thread. Violations go through the FlagDispatcher to the main thread; a cancel for Timer becomes a moving
 * set-back (NCP/Watchdog "lagback").
 */
public final class NetChecks {
    /** Performs a set-back on the main thread (provided by the moving module). */
    public interface Setback { void request(Player p, CheckType cause); }

    private final FlagDispatcher dispatcher;
    private final Setback setback;
    private final Set<CheckType> enabled = EnumSet.of(CheckType.NET_TIMER, CheckType.NET_FLYINGFREQUENCY,
            CheckType.NET_KEEPALIVE, CheckType.NET_PACKETORDER);

    public NetChecks(FlagDispatcher dispatcher, Setback setback) {
        this.dispatcher = dispatcher;
        this.setback = setback;
    }

    public void setEnabled(CheckType type, boolean on) {
        if (on) enabled.add(type); else enabled.remove(type);
    }

    public boolean isEnabled(CheckType type) { return enabled.contains(type); }

    void flag(Player p, CheckType type, double vl, String details, boolean setbackOnCancel) {
        if (!enabled.contains(type)) return;
        dispatcher.flag(p, type, vl, details, setbackOnCancel ? new FlagDispatcher.OnCancel() {
            @Override public void run(Player pl) { setback.request(pl, type); }
        } : null);
    }

    /** Every serverbound flying packet (C03-C06). teleportConfirm: the C06 answering a server teleport. */
    public void onFlying(Player p, NetData d, double now, boolean teleportConfirm) {
        TimerCheck.onFlying(this, p, d, now, teleportConfirm);
        FlyingFrequency.onFlying(this, p, d, now);
        KeepAliveCheck.onFlying(this, p, d);
        PacketOrder.onFlying(this, p, d, now);
    }

    /** Non-flying serverbound packets relevant to the net group. */
    public void onInbound(Player p, NetData d, Packet<?> packet, double now) {
        if (packet instanceof PacketPlayInKeepAlive) {
            KeepAliveCheck.onInbound(this, p, d, ((PacketPlayInKeepAlive) packet).a());
        } else if (packet instanceof PacketPlayInUseEntity) {
            PacketPlayInUseEntity u = (PacketPlayInUseEntity) packet;
            if (u.a() == PacketPlayInUseEntity.EnumEntityUseAction.ATTACK) PacketOrder.onAttack(this, p, d, now);
        } else if (packet instanceof PacketPlayInBlockPlace) {
            PacketPlayInBlockPlace b = (PacketPlayInBlockPlace) packet;
            PacketOrder.onBlockPlace(d, b.getFace(), b.getItemStack());
        } else if (packet instanceof PacketPlayInBlockDig) {
            PacketPlayInBlockDig.EnumPlayerDigType type = ((PacketPlayInBlockDig) packet).c();
            if (type == PacketPlayInBlockDig.EnumPlayerDigType.RELEASE_USE_ITEM) PacketOrder.onReleaseUseItem(d);
            else if (type == PacketPlayInBlockDig.EnumPlayerDigType.DROP_ITEM
                    || type == PacketPlayInBlockDig.EnumPlayerDigType.DROP_ALL_ITEMS) PacketOrder.onHeldItemChange(d);
        } else if (packet instanceof PacketPlayInWindowClick) {
            // The held sword may have been moved away: the client stops using it without a C07.
            PacketOrder.onHeldItemChange(d);
        } else if (packet instanceof PacketPlayInEntityAction) {
            PacketPlayInEntityAction.EnumPlayerAction a = ((PacketPlayInEntityAction) packet).b();
            if (a != null) PacketOrder.onEntityAction(d, a.name());
        } else if (packet instanceof PacketPlayInHeldItemSlot) {
            PacketOrder.onHeldItemChange(d);
        }
    }

    /** Clientbound keep-alive observed. */
    public void onKeepAliveOut(NetData d, int id) {
        KeepAliveCheck.onOutbound(d, id);
    }
}
