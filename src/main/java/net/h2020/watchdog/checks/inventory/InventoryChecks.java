package net.h2020.watchdog.checks.inventory;

import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.PacketCheck;
import net.h2020.watchdog.framework.PacketRouter;
import net.h2020.watchdog.framework.PlayerData;
import net.h2020.watchdog.framework.Reflect;
import net.minecraft.server.v1_8_R3.Item;
import net.minecraft.server.v1_8_R3.ItemBow;
import net.minecraft.server.v1_8_R3.ItemFood;
import net.minecraft.server.v1_8_R3.ItemMilkBucket;
import net.minecraft.server.v1_8_R3.ItemPotion;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.PacketPlayInClientCommand;
import net.minecraft.server.v1_8_R3.PacketPlayInCloseWindow;
import net.minecraft.server.v1_8_R3.PacketPlayInEntityAction;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInHeldItemSlot;
import net.minecraft.server.v1_8_R3.PacketPlayInSetCreativeSlot;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayInWindowClick;
import net.minecraft.server.v1_8_R3.PacketPlayOutCloseWindow;
import net.minecraft.server.v1_8_R3.PacketPlayOutOpenWindow;
import net.minecraft.server.v1_8_R3.PacketPlayOutRespawn;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Inventory check group (NCP InventoryListener): FastClick, InstantBow, InstantEat, FastConsume, Open, Items. */
public final class InventoryChecks implements Listener, PacketCheck {
    private final WatchdogServiceImpl service;
    private final FastClick fastClick;
    private final InstantBow instantBow;
    private final InstantEat instantEat;
    private final FastConsume fastConsume;
    private final Open open;
    private final Items items;

    private InventoryChecks(WatchdogServiceImpl s) {
        this.service = s;
        fastClick = new FastClick(s);
        instantBow = new InstantBow(s);
        instantEat = new InstantEat(s);
        fastConsume = new FastConsume(s);
        open = new Open(s);
        items = new Items(s);
    }

    public static void register(WatchdogServiceImpl service, PacketRouter router, JavaPlugin plugin) {
        InventoryChecks c = new InventoryChecks(service);
        router.add(c);
        service.registerListener(c);
    }

    // ------------------------------------------------------------------ packets

    @Override
    public boolean onPacket(Player p, PlayerData d, Packet<?> packet, long now) {
        InventoryData id = d.inv;
        if (packet instanceof PacketPlayInClientCommand) {
            if (((PacketPlayInClientCommand) packet).a() == PacketPlayInClientCommand.EnumClientCommand.OPEN_INVENTORY_ACHIEVEMENT) {
                id.invOpen = true;
                id.containerOpen = false;
                id.windowId = 0;
                id.openMs = now;
                id.clicksSinceOpen = 0;
            }
        } else if (packet instanceof PacketPlayInCloseWindow) {
            id.closeAll();
        } else if (packet instanceof PacketPlayInWindowClick) {
            PacketPlayInWindowClick c = (PacketPlayInWindowClick) packet;
            if (c.a() == 0) open.onOwnInventoryClick(p, d, now);
            fastClick.onClick(p, d, c.a(), c.f(), now);
        } else if (packet instanceof PacketPlayInUseEntity) {
            if (((PacketPlayInUseEntity) packet).a() == PacketPlayInUseEntity.EnumEntityUseAction.ATTACK) {
                return !open.onAttack(p, d, now);
            }
        } else if (packet instanceof PacketPlayInEntityAction) {
            if (((PacketPlayInEntityAction) packet).b() == PacketPlayInEntityAction.EnumPlayerAction.START_SPRINTING) {
                open.onSprint(p, d, now);
            }
        } else if (packet instanceof PacketPlayInBlockPlace) {
            PacketPlayInBlockPlace bp = (PacketPlayInBlockPlace) packet;
            ItemStack item = bp.getItemStack();
            if (bp.getFace() == 255 && item != null) {
                Item it = item.getItem();
                if (it instanceof ItemBow) {
                    if (id.bowStartMs == 0 || now - id.bowStartMs > 3000) id.bowStartMs = now;
                } else if (it instanceof ItemFood || it instanceof ItemMilkBucket
                        || (it instanceof ItemPotion && (item.getData() & 16384) == 0)) {
                    if (id.useStartMs == 0 || now - id.useStartMs > 2000) id.useStartMs = now;
                }
            }
        } else if (packet instanceof PacketPlayInBlockDig) {
            if (((PacketPlayInBlockDig) packet).c() == PacketPlayInBlockDig.EnumPlayerDigType.RELEASE_USE_ITEM) {
                id.bowReleaseMs = now;
                id.useStartMs = 0;
            }
        } else if (packet instanceof PacketPlayInHeldItemSlot) {
            id.bowStartMs = 0;
            id.useStartMs = 0;
        } else if (packet instanceof PacketPlayInSetCreativeSlot) {
            return !items.onCreativeSlot(p, ((PacketPlayInSetCreativeSlot) packet).getItemStack());
        }
        return true;
    }

    @Override
    public boolean onFlying(final Player p, PlayerData d, PacketPlayInFlying f, long now) {
        if (open.onTickEnd(p, d, now)) {
            d.inv.closeAll();
            service.runMain(() -> { if (p.isOnline()) p.closeInventory(); });
        }
        return true;
    }

    @Override
    public void onOutbound(Player p, PlayerData d, Packet<?> packet, long now) {
        InventoryData id = d.inv;
        if (packet instanceof PacketPlayOutOpenWindow) {
            id.invOpen = false;
            id.containerOpen = true;
            id.windowId = Reflect.openWindowId((PacketPlayOutOpenWindow) packet);
            id.openMs = now;
            id.clicksSinceOpen = 0;
        } else if (packet instanceof PacketPlayOutCloseWindow) {
            id.closeAll();
        } else if (packet instanceof PacketPlayOutRespawn) {
            id.closeAll();
            id.bowStartMs = 0;
            id.useStartMs = 0;
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        final Player p = (Player) e.getWhoClicked();
        PlayerData d = service.data(p);
        boolean cancel = false;
        synchronized (d) {
            InventoryData id = d.inv;
            if (id.pendingCancels > 0) {
                if (System.currentTimeMillis() <= id.pendingCancelUntil) {
                    id.pendingCancels--;
                    cancel = true;
                } else {
                    id.pendingCancels = 0;
                }
            }
            if (items.onClickItem(p, e.getCurrentItem()) || items.onClickItem(p, e.getCursor())) cancel = true;
        }
        if (cancel) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(service.plugin(), () -> { if (p.isOnline()) p.updateInventory(); });
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        final Player p = (Player) e.getEntity();
        PlayerData d = service.data(p);
        boolean cancel;
        synchronized (d) { cancel = instantBow.check(p, d, e.getForce(), System.currentTimeMillis()); }
        if (cancel) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(service.plugin(), () -> { if (p.isOnline()) p.updateInventory(); });
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        PlayerData d = service.data(p);
        boolean cancel = false;
        synchronized (d) {
            InventoryData id = d.inv;
            long start = id.useStartMs;
            id.useStartMs = 0;
            if (start > 0) {
                long elapsed = System.currentTimeMillis() - start;
                String item = e.getItem() == null ? "?" : e.getItem().getType().name().toLowerCase();
                if (instantEat.check(p, elapsed, item)) cancel = true;
                if (fastConsume.check(p, elapsed, item)) cancel = true;
            }
        }
        if (cancel) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        PlayerData d = service.dataIfPresent(e.getEntity().getUniqueId());
        if (d == null) return;
        synchronized (d) {
            d.inv.closeAll();
            d.inv.useStartMs = 0;
            d.inv.bowStartMs = 0;
        }
    }
}
