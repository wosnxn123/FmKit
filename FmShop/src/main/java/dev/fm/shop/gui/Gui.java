package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.MarketState;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.tx.TxEngine;
import dev.fm.shop.util.ItemNames;
import dev.fm.shop.util.Money;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Shared menu furniture: backgrounds, footer icons, and price lore. */
final class Gui {

    private Gui() {
    }

    static void frame(FmShopPlugin plugin, Inventory inv) {
        ItemStack filler = Icons.of(plugin.settings().icon("filler", Material.GRAY_STAINED_GLASS_PANE), " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    static void fillRow(FmShopPlugin plugin, Inventory inv, int from) {
        ItemStack filler = Icons.of(plugin.settings().icon("filler", Material.GRAY_STAINED_GLASS_PANE), " ");
        for (int i = from; i < Math.min(from + 9, inv.getSize()); i++) {
            inv.setItem(i, filler);
        }
    }

    static ItemStack balanceIcon(FmShopPlugin plugin, Player p) {
        PlayerData d = plugin.data().loadSync(p.getUniqueId());
        String cur = plugin.settings().currency();
        return Icons.of(plugin.settings().icon("balance", Material.GOLD_INGOT),
                "<green>我的余额",
                List.of("<white>" + Money.format(d.balance(), cur),
                        "",
                        "<gray>累计支出 <white>" + Money.format(d.totalSpent(), cur),
                        "<gray>累计收入 <white>" + Money.format(d.totalEarned(), cur)));
    }

    /**
     * Per-item lore: both prices, the live market multiplier, what the player
     * holds, and remaining daily quota.
     *
     * <p>Prices shown are unit prices at this instant. A trade recomputes them,
     * so a menu left open across a price move charges the new price and says so
     * in chat rather than honouring a stale number.
     */
    static List<String> priceLore(FmShopPlugin plugin, PriceEntry e, Player p, long now) {
        String cur = plugin.settings().currency();
        List<String> lore = new ArrayList<>(8);
        if (e.buyable()) {
            lore.add("<green>左键买入 <white>" + Money.format(plugin.market().buyUnit(e, now), cur) + "</white> /个");
        } else {
            lore.add("<dark_gray>不出售");
        }
        if (e.sellable()) {
            lore.add("<gold>右键卖出 <white>" + Money.format(plugin.market().sellUnit(e, now), cur) + "</white> /个");
        } else {
            lore.add("<dark_gray>不回收");
        }
        if (e.dynamic()) {
            int bp = plugin.market().multiplierBp(e, now);
            int percent = Math.round(bp / 100f);
            String trend = bp > MarketState.BASE_BP ? "<red>↑ 走高"
                    : bp < MarketState.BASE_BP ? "<green>↓ 走低" : "<gray>持平";
            lore.add("<gray>行情 <white>" + percent + "%</white> " + trend);
        }
        int have = TxEngine.count(p.getInventory(), new ItemStack(e.material()));
        lore.add("");
        lore.add("<gray>背包持有 <white>" + have);
        PlayerData d = plugin.data().loadSync(p.getUniqueId());
        long today = plugin.settings().today();
        if (e.dailyBuy() > 0) {
            lore.add("<gray>今日可买 <white>" + Math.max(0, e.dailyBuy() - d.boughtToday(e.material(), today)));
        }
        if (e.dailySell() > 0) {
            lore.add("<gray>今日可卖 <white>" + Math.max(0, e.dailySell() - d.soldToday(e.material(), today)));
        }
        lore.add("");
        if (e.buyable()) {
            lore.add("<yellow>左键 <gray>选择数量买入");
        }
        if (e.sellable()) {
            lore.add("<yellow>右键 <gray>选择数量卖出");
            if (have > 0) {
                lore.add("<yellow>Shift+右键 <gray>立即卖出全部持有");
            }
        }
        return lore;
    }

    static ItemStack itemIcon(FmShopPlugin plugin, PriceEntry e, Player p, long now) {
        return Icons.of(e.material(), "<white>" + ItemNames.plain(e.material()),
                priceLore(plugin, e, p, now));
    }

    static void click(FmShopPlugin plugin, Player p) {
        if (plugin.settings().sounds()) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        }
    }

    static void deny(FmShopPlugin plugin, Player p) {
        if (plugin.settings().sounds()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        }
    }
}
