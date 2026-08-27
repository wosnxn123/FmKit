package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.Category;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Shop landing screen: one icon per category, plus balance and bulk sell.
 *
 * <p>Categories occupy two rows so a table can grow to fourteen without paging
 * the hub itself. Empty categories are hidden - an icon that opens nothing is
 * worse than no icon.
 */
public final class HubView extends View {

    private static final int[] CATEGORY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
    };
    private static final int SLOT_BALANCE = 29;
    private static final int SLOT_SELL_ALL = 31;
    private static final int SLOT_CLOSE = 33;

    private final List<Category> shown = new ArrayList<>();

    public HubView(FmShopPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Icons.plain(plugin.settings().msg("gui-title-hub"));
    }

    @Override
    protected int size() {
        return 36;
    }

    @Override
    public void render() {
        shown.clear();
        for (Category c : plugin.prices().categories()) {
            if (!plugin.prices().items(c.id()).isEmpty()) {
                shown.add(c);
            }
        }
        Gui.frame(plugin, getInventory());
        for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
            int slot = CATEGORY_SLOTS[i];
            if (i >= shown.size()) {
                getInventory().setItem(slot, null);
                continue;
            }
            Category c = shown.get(i);
            getInventory().setItem(slot, Icons.of(c.icon(),
                    "<gold>" + c.display(),
                    List.of("<gray>" + plugin.prices().items(c.id()).size() + " 种商品",
                            "",
                            "<yellow>点击查看")));
        }
        getInventory().setItem(SLOT_BALANCE, Gui.balanceIcon(plugin, player));
        getInventory().setItem(SLOT_SELL_ALL, Icons.of(plugin.settings().icon("bag", Material.CHEST),
                "<aqua>一键回收",
                List.of("<gray>卖出背包中所有可回收物品", "", "<yellow>点击预览")));
        getInventory().setItem(SLOT_CLOSE, Icons.of(plugin.settings().icon("close", Material.BARRIER),
                "<red>关闭"));
    }

    @Override
    public void click(int slot, ClickType type) {
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_SELL_ALL) {
            new SellAllView(plugin, player).open();
            return;
        }
        for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
            if (CATEGORY_SLOTS[i] == slot && i < shown.size()) {
                new CategoryView(plugin, player, shown.get(i).id(), 0).open();
                return;
            }
        }
    }
}
