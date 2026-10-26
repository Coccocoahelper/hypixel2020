package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.BedwarsShopItem;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a 2020 Bed Wars player buys on a shop trip, in the order they'd click it, given their role, phase and
 * wallet. Purchases are simulated against the wallet so the list is affordable as a whole.
 */
public final class ShopPlanner {
    private ShopPlanner() {}

    public static int iron(Player p) { return Inv.count(p, Material.IRON_INGOT); }
    public static int gold(Player p) { return Inv.count(p, Material.GOLD_INGOT); }
    public static int diamonds(Player p) { return Inv.count(p, Material.DIAMOND); }
    public static int emeralds(Player p) { return Inv.count(p, Material.EMERALD); }

    public static int wool(Player p) { return Inv.count(p, Inv.type(Material.WOOL)); }

    /** 0 none/wood, 2 stone, 3 iron, 4 diamond. */
    public static int swordTier(Player p) {
        int t = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (Items.empty(i)) continue;
            switch (i.getType()) {
                case DIAMOND_SWORD: t = Math.max(t, 4); break;
                case IRON_SWORD: t = Math.max(t, 3); break;
                case STONE_SWORD: t = Math.max(t, 2); break;
                case WOOD_SWORD: t = Math.max(t, 1); break;
                default: break;
            }
        }
        return t;
    }

    /** 1 leather, 2 chain, 3 iron, 4 diamond (from boots, which the permanent armor upgrade replaces). */
    public static int armorTier(Player p) {
        ItemStack b = p.getInventory().getBoots();
        if (Items.empty(b)) return 0;
        switch (b.getType()) {
            case DIAMOND_BOOTS: return 4;
            case IRON_BOOTS: return 3;
            case CHAINMAIL_BOOTS: return 2;
            default: return 1;
        }
    }

    public static final class Wallet {
        public int iron, gold, diamond, emerald;
        Wallet(Player p) { iron = iron(p); gold = gold(p); diamond = diamonds(p); emerald = emeralds(p); }

        boolean take(BedwarsShopItem it) {
            switch (it.currency) {
                case IRON: if (iron < it.cost) return false; iron -= it.cost; return true;
                case GOLD: if (gold < it.cost) return false; gold -= it.cost; return true;
                case DIAMOND: if (diamond < it.cost) return false; diamond -= it.cost; return true;
                case EMERALD: if (emerald < it.cost) return false; emerald -= it.cost; return true;
                default: return false;
            }
        }
    }

    public static List<BedwarsShopItem> plan(Player p, Role role, boolean tryhard, int gameSeconds, boolean finalLife) {
        Wallet w = new Wallet(p);
        List<BedwarsShopItem> out = new ArrayList<>();
        int blocks = Inv.count(p, Inv.BLOCK);
        int sword = swordTier(p);
        int armor = armorTier(p);
        boolean hasPick = Inv.has(p, Inv.PICKAXE), hasShears = Inv.has(p, Inv.SHEARS), hasAxe = Inv.has(p, Inv.AXE);

        // 1) wool first: everybody needs blocks. Once a stack is in hand, chainmail (40 iron) comes before more wool.
        int woolWanted = role == Role.RUSHER ? 48 : role == Role.DEFENDER ? 32 : 32;
        if (finalLife) woolWanted += 16;
        boolean saveForChain = armor < 2 && (role != Role.RUSHER || gameSeconds > 90);
        while (blocks < woolWanted && (!saveForChain || blocks < 32 || w.iron >= 40 + 4) && w.take(BedwarsShopItem.WOOL)) {
            out.add(BedwarsShopItem.WOOL); blocks += 16;
        }
        if (saveForChain && w.iron >= 40 && blocks >= 16 && w.take(BedwarsShopItem.CHAINMAIL_ARMOR)) {
            out.add(BedwarsShopItem.CHAINMAIL_ARMOR); armor = 2;
        }

        // 2) defenders: end stone for the second layer
        if (role == Role.DEFENDER && Inv.count(p, Inv.type(Material.ENDER_STONE)) < 12 && w.iron >= 24 + 4) {
            if (w.take(BedwarsShopItem.END_STONE)) out.add(BedwarsShopItem.END_STONE);
        }
        // 3) sword
        if (sword < 2 && w.take(BedwarsShopItem.STONE_SWORD)) { out.add(BedwarsShopItem.STONE_SWORD); sword = 2; }
        if (sword < 3 && w.gold >= 7 + (armor < 3 ? 12 : 0) && w.take(BedwarsShopItem.IRON_SWORD)) { out.add(BedwarsShopItem.IRON_SWORD); sword = 3; }
        if (sword < 4 && w.emerald >= 4 && (tryhard || gameSeconds > 600) && w.take(BedwarsShopItem.DIAMOND_SWORD)) out.add(BedwarsShopItem.DIAMOND_SWORD);
        // 4) armor (permanent)
        if (armor < 4 && w.emerald >= 6 && w.take(BedwarsShopItem.DIAMOND_ARMOR)) { out.add(BedwarsShopItem.DIAMOND_ARMOR); armor = 4; }
        if (armor < 3 && w.take(BedwarsShopItem.IRON_ARMOR)) { out.add(BedwarsShopItem.IRON_ARMOR); armor = 3; }
        if (armor < 2 && w.iron >= 40 && (role != Role.RUSHER || gameSeconds > 90) && w.take(BedwarsShopItem.CHAINMAIL_ARMOR)) {
            out.add(BedwarsShopItem.CHAINMAIL_ARMOR); armor = 2;
        }
        // 5) tools
        if (role == Role.RUSHER && !hasShears && w.take(BedwarsShopItem.SHEARS)) out.add(BedwarsShopItem.SHEARS);
        if (!hasPick && (role != Role.DEFENDER || gameSeconds > 240) && w.iron >= 10 + (blocks < 16 ? 4 : 0) && w.take(BedwarsShopItem.PICKAXE)) out.add(BedwarsShopItem.PICKAXE);
        if (!hasAxe && tryhard && gameSeconds > 300 && w.take(BedwarsShopItem.AXE)) out.add(BedwarsShopItem.AXE);
        // 6) utility for sweats / late game
        if (tryhard || gameSeconds > 240) {
            if (armor >= 2 && Inv.count(p, Inv.FIREBALL) < 2 && w.iron >= 40 && w.take(BedwarsShopItem.FIREBALL)) out.add(BedwarsShopItem.FIREBALL);
            if ((tryhard || gameSeconds > 600) && !Inv.has(p, Inv.PEARL) && w.take(BedwarsShopItem.ENDER_PEARL)) out.add(BedwarsShopItem.ENDER_PEARL);
            if (role == Role.RUSHER && gameSeconds > 300 && Inv.count(p, Inv.TNT) < 1 && w.take(BedwarsShopItem.TNT)) out.add(BedwarsShopItem.TNT);
        }
        if (Inv.count(p, Inv.GAPPLE) < (finalLife ? 2 : 1) && w.gold >= 3 && (gameSeconds > 200 || finalLife) && w.take(BedwarsShopItem.GOLDEN_APPLE)) {
            out.add(BedwarsShopItem.GOLDEN_APPLE);
        }
        if (role == Role.FARMER && gameSeconds > 360 && !Inv.has(p, Inv.BOW) && w.gold >= 12 + 2 && w.take(BedwarsShopItem.BOW)) {
            out.add(BedwarsShopItem.BOW);
            if (w.take(BedwarsShopItem.ARROW)) out.add(BedwarsShopItem.ARROW);
        }
        // leftover iron -> more wool (people hoard blocks)
        if (blocks < 64 && w.iron >= 12 && armor >= 2 && w.take(BedwarsShopItem.WOOL)) out.add(BedwarsShopItem.WOOL);
        return out;
    }

    /** Team upgrades worth buying now, in priority order. */
    public static List<String> upgrades(Role role, boolean teams) {
        List<String> out = new ArrayList<>();
        if (teams) {
            out.add("sharpness"); out.add("protection"); out.add("forge"); out.add("heal_pool"); out.add("trap_it_is_a_trap");
        } else {
            out.add("sharpness"); out.add("protection"); out.add("forge"); out.add("trap_it_is_a_trap"); out.add("haste");
        }
        return out;
    }
}
