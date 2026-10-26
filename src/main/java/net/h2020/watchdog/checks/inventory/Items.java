package net.h2020.watchdog.checks.inventory;

import net.h2020.api.watchdog.CheckType;
import net.h2020.watchdog.WatchdogServiceImpl;
import net.h2020.watchdog.framework.Check;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * NCP Inventory.Items: illegal items. In 1.8 the only client-authored item packet is C10 (creative inventory):
 * rejected outside creative, and in creative rejected for impossible stacks (size outside 1..64, enchantment levels
 * above 10, more than 12 enchantments, "Unbreakable" or absurd attribute modifiers). Server-side item stacks
 * clicked in windows are also checked for impossible sizes (dupes).
 */
public final class Items extends Check {
    public Items(WatchdogServiceImpl service) { super(service, CheckType.INVENTORY_ITEMS); }

    /** Packet thread, C10. @return true to drop the packet */
    public boolean onCreativeSlot(Player p, net.minecraft.server.v1_8_R3.ItemStack item) {
        if (exempt(p)) return false;
        if (p.getGameMode() != GameMode.CREATIVE) {
            flag(p, 1.0, "creative slot packet in " + p.getGameMode().name().toLowerCase());
            return true;
        }
        if (item == null) return false;
        String why = illegal(item);
        if (why == null) return false;
        flag(p, 1.0, why);
        return true;
    }

    /** Main thread, InventoryClickEvent items. @return cancel */
    public boolean onClickItem(Player p, ItemStack is) {
        if (is == null || exempt(p)) return false;
        int amount = is.getAmount();
        if (amount > 64 || amount < 0) return flag(p, 1.0, "stack size " + amount + " " + is.getType().name().toLowerCase());
        return false;
    }

    static String illegal(net.minecraft.server.v1_8_R3.ItemStack item) {
        if (item.count > 64 || item.count < 1) return "stack size " + item.count;
        NBTTagCompound tag = item.getTag();
        if (tag == null) return null;
        if (tag.getBoolean("Unbreakable")) return "unbreakable tag";
        NBTTagList ench = tag.getList("ench", 10);
        if (ench != null) {
            if (ench.size() > 12) return "enchantments=" + ench.size();
            for (int i = 0; i < ench.size(); i++) {
                short lvl = ench.get(i).getShort("lvl");
                if (lvl > 10 || lvl < 0) return "enchant level " + lvl;
            }
        }
        NBTTagList attrs = tag.getList("AttributeModifiers", 10);
        if (attrs != null) {
            for (int i = 0; i < attrs.size(); i++) {
                double amount = attrs.get(i).getDouble("Amount");
                if (Math.abs(amount) > 100) return "attribute modifier " + amount;
            }
        }
        return null;
    }
}
