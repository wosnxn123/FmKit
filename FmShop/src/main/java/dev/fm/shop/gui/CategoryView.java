package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.Category;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.tx.TxReport;
import dev.fm.shop.tx.TxResult;
import dev.fm.shop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/**
 * One page of a category's items.
 *
 * <p>Both directions live on the same icon - left buys, right sells - so a
 * player never has to find a mode switch to undo a purchase. Shift+right sells
 * everything held of that item immediately, which is the one shortcut worth
 * having: it is the common case and it cannot overspend, only overpay in regret.
 */
public final class CategoryView extends View {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BALANCE = 47;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_PAGE = 51;
    private static final int SLOT_NEXT = 53;

    private final String categoryId;
    private int page;
    private final List<String> items = new ArrayList<>();

    public CategoryView(FmShopPlugin plugin, Player player, String categoryId, int page) {
        super(plugin, player);
        this.categoryId = categoryId;
        this.page = Math.max(0, page);
    }

    @Override
    protected Component title() {
        String name = categoryId;
        for (Category c : plugin.prices().categories()) {
            if (c.id().equals(categoryId)) {
                name = c.display();
                break;
            }
        }
        return Icons.plain(TextUtil.apply(plugin.settings().msg("gui-title-buy"), "category", name));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void render() {
        items.clear();
        items.addAll(plugin.prices().items(categoryId));
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) {
            page = pages - 1;
        }
        long now = System.currentTimeMillis();
        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int idx = start + slot;
            if (idx >= items.size()) {
                getInventory().setItem(slot, null);
                continue;
            }
            PriceEntry e = plugin.prices().get(items.get(idx));
            getInventory().setItem(slot, e == null ? null : Gui.itemIcon(plugin, e, player, now));
        }
        Gui.fillRow(plugin, getInventory(), 45);
        if (page > 0) {
            getInventory().setItem(SLOT_PREV, Icons.of(plugin.settings().icon("prev", Material.ARROW),
                    "<yellow>上一页"));
        }
        if (page < pages - 1) {
            getInventory().setItem(SLOT_NEXT, Icons.of(plugin.settings().icon("next", Material.ARROW),
                    "<yellow>下一页"));
        }
        getInventory().setItem(SLOT_BALANCE, Gui.balanceIcon(plugin, player));
        getInventory().setItem(SLOT_BACK, Icons.of(plugin.settings().icon("back", Material.ARROW),
                "<white>返回商店"));
        getInventory().setItem(SLOT_PAGE, Icons.of(Material.PAPER,
                "<gray>第 <white>" + (page + 1) + "</white>/<white>" + pages + "</white> 页",
                List.of("<gray>共 <white>" + items.size() + "</white> 种商品")));
    }

    @Override
    public void click(int slot, ClickType type) {
        switch (slot) {
            case SLOT_BACK -> {
                Gui.click(plugin, player);
                new HubView(plugin, player).open();
                return;
            }
            case SLOT_PREV -> {
                if (page > 0) {
                    page--;
                    Gui.click(plugin, player);
                    render();
                }
                return;
            }
            case SLOT_NEXT -> {
                page++;
                Gui.click(plugin, player);
                render();
                return;
            }
            default -> {
            }
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            return;
        }
        int idx = page * PAGE_SIZE + slot;
        if (idx >= items.size()) {
            return;
        }
        PriceEntry e = plugin.prices().get(items.get(idx));
        if (e == null) {
            return;
        }
        boolean buying = !type.isRightClick();
        if (buying && Gui.locked(plugin, e, player)) {
            Gui.deny(plugin, player);
            TxReport.tell(plugin, player, e.key(), TxResult.fail("locked"));
            return;
        }
        if (buying ? !e.buyable() : !e.sellable()) {
            Gui.deny(plugin, player);
            return;
        }
        if (type == ClickType.SHIFT_RIGHT) {
            // Dump everything held of this item; the engine clamps to quota,
            // stock on hand, and max-per-action.
            TxResult r = plugin.tx().sell(player, e, plugin.settings().maxPerAction());
            TxReport.tell(plugin, player, e.key(), r);
            render();
            return;
        }
        Gui.click(plugin, player);
        new ConfirmView(plugin, player, e, buying, categoryId, page).open();
    }
}
