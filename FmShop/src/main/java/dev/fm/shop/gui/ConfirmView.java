package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.tx.TxEngine;
import dev.fm.shop.tx.TxReport;
import dev.fm.shop.tx.TxResult;
import dev.fm.shop.util.Money;
import dev.fm.shop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Quantity picker and price preview for one item in one direction.
 *
 * <p>The order is capped by whatever binds first - daily quota, inventory space,
 * stock on hand, balance, or {@code max-per-action} - and the cap is shown with
 * its reason, so a clamped order never looks like a bug. Totals include fees,
 * because a preview that omits the fee is a lie the player discovers after
 * paying.
 */
public final class ConfirmView extends View {

    private static final int SLOT_MINUS_64 = 10;
    private static final int SLOT_MINUS_16 = 11;
    private static final int SLOT_MINUS_1 = 12;
    private static final int SLOT_PREVIEW = 13;
    private static final int SLOT_PLUS_1 = 14;
    private static final int SLOT_PLUS_16 = 15;
    private static final int SLOT_PLUS_64 = 16;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_STACK = 21;
    private static final int SLOT_CONFIRM = 22;
    private static final int SLOT_MAX = 23;
    private static final int SLOT_BALANCE = 26;

    private final PriceEntry entry;
    private final boolean buying;
    private final String categoryId;
    private final int categoryPage;
    private int qty = 1;

    public ConfirmView(FmShopPlugin plugin, Player player, PriceEntry entry, boolean buying,
                       String categoryId, int categoryPage) {
        super(plugin, player);
        this.entry = entry;
        this.buying = buying;
        this.categoryId = categoryId;
        this.categoryPage = categoryPage;
    }

    @Override
    protected Component title() {
        return Icons.plain(TextUtil.apply(plugin.settings().msg("gui-title-confirm"),
                "item", entry.key().mini()));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    public void render() {
        long now = System.currentTimeMillis();
        Cap cap = cap(now);
        qty = Math.max(1, Math.min(qty, Math.max(1, cap.max())));
        Gui.frame(plugin, getInventory());

        getInventory().setItem(SLOT_MINUS_64, step(-64));
        getInventory().setItem(SLOT_MINUS_16, step(-16));
        getInventory().setItem(SLOT_MINUS_1, step(-1));
        getInventory().setItem(SLOT_PLUS_1, step(1));
        getInventory().setItem(SLOT_PLUS_16, step(16));
        getInventory().setItem(SLOT_PLUS_64, step(64));

        String cur = plugin.settings().currency();
        long unit = buying ? plugin.market().buyUnit(entry, now) : plugin.market().sellUnit(entry, now);
        long gross = Money.times(unit, qty);
        long fee = fee(gross);
        long total = buying ? Money.add(gross, fee) : gross - fee;

        List<String> lore = new ArrayList<>(10);
        lore.add("<gray>单价 <white>" + Money.format(unit, cur));
        lore.add("<gray>数量 <white>" + qty);
        if (fee > 0) {
            lore.add("<gray>小计 <white>" + Money.format(gross, cur));
            lore.add("<gray>手续费 <white>" + Money.format(fee, cur));
        }
        lore.add(buying
                ? "<green>应付 <white>" + Money.format(total, cur)
                : "<gold>可得 <white>" + Money.format(total, cur));
        lore.add("");
        lore.add("<gray>上限 <white>" + cap.max() + "</white> <dark_gray>(" + cap.reason() + ")");
        getInventory().setItem(SLOT_PREVIEW, Icons.of(entry.probe(), qty,
                (buying ? "<green>买入 " : "<gold>卖出 ") + entry.key().mini(), lore));

        getInventory().setItem(SLOT_BACK, Icons.of(plugin.settings().icon("back", Material.ARROW),
                "<white>返回", List.of("<gray>不进行交易")));
        getInventory().setItem(SLOT_STACK, Icons.of(Material.PAPER, "<yellow>一组 (64)"));
        getInventory().setItem(SLOT_MAX, Icons.of(Material.PAPER,
                "<yellow>最大 (" + cap.max() + ")", List.of("<dark_gray>" + cap.reason())));
        getInventory().setItem(SLOT_CONFIRM, cap.max() > 0
                ? Icons.of(plugin.settings().icon("confirm", Material.LIME_DYE),
                "<green>确认" + (buying ? "购买" : "出售"),
                List.of(buying
                        ? "<gray>支付 <white>" + Money.format(total, cur)
                        : "<gray>获得 <white>" + Money.format(total, cur)))
                : Icons.of(plugin.settings().icon("cancel", Material.RED_DYE),
                "<red>无法交易", List.of("<gray>" + cap.reason())));
        getInventory().setItem(SLOT_BALANCE, Gui.balanceIcon(plugin, player));
    }

    @Override
    public void click(int slot, ClickType type) {
        switch (slot) {
            case SLOT_BACK -> {
                Gui.click(plugin, player);
                new CategoryView(plugin, player, categoryId, categoryPage).open();
            }
            case SLOT_MINUS_64 -> bump(-64);
            case SLOT_MINUS_16 -> bump(-16);
            case SLOT_MINUS_1 -> bump(-1);
            case SLOT_PLUS_1 -> bump(1);
            case SLOT_PLUS_16 -> bump(16);
            case SLOT_PLUS_64 -> bump(64);
            case SLOT_STACK -> {
                qty = 64;
                Gui.click(plugin, player);
                render();
            }
            case SLOT_MAX -> {
                qty = Math.max(1, cap(System.currentTimeMillis()).max());
                Gui.click(plugin, player);
                render();
            }
            case SLOT_CONFIRM -> confirm();
            default -> {
            }
        }
    }

    private void confirm() {
        TxResult r = buying
                ? plugin.tx().buy(player, entry, qty)
                : plugin.tx().sell(player, entry, qty);
        TxReport.tell(plugin, player, entry.key(), r);
        if (!r.ok()) {
            Gui.deny(plugin, player);
        }
        if (live()) {
            render();
        }
    }

    private void bump(int delta) {
        int max = Math.max(1, cap(System.currentTimeMillis()).max());
        int next = Math.max(1, Math.min(max, qty + delta));
        if (next == qty) {
            Gui.deny(plugin, player);
            return;
        }
        qty = next;
        Gui.click(plugin, player);
        render();
    }

    private ItemStack step(int delta) {
        Material mat = delta > 0
                ? plugin.settings().icon("buy", Material.EMERALD)
                : plugin.settings().icon("cancel", Material.RED_DYE);
        return Icons.of(mat, (delta > 0 ? "<green>+" : "<red>") + delta);
    }

    private long fee(long gross) {
        if (player.hasPermission(TxEngine.FEE_EXEMPT)) {
            return 0;
        }
        int bp = buying ? plugin.settings().buyFeeBp() : plugin.settings().sellFeeBp();
        return Money.basisPoints(gross, bp);
    }

    /** The binding limit on this order and the human reason for it. */
    private record Cap(int max, String reason) {
    }

    private Cap cap(long now) {
        PlayerData d = plugin.data().loadSync(player.getUniqueId());
        long today = plugin.settings().today();
        int max = plugin.settings().maxPerAction();
        String reason = "单次上限";
        if (buying) {
            if (entry.dailyBuy() > 0) {
                int left = Math.max(0, entry.dailyBuy() - d.boughtToday(entry.id(), today));
                if (left < max) {
                    max = left;
                    reason = "今日限购";
                }
            }
            int room = TxEngine.space(player.getInventory(), entry.probe());
            if (room < max) {
                max = room;
                reason = "背包空间";
            }
            int afford = affordable(now, max);
            if (afford < max) {
                max = afford;
                reason = "余额";
            }
        } else {
            if (entry.dailySell() > 0) {
                int left = Math.max(0, entry.dailySell() - d.soldToday(entry.id(), today));
                if (left < max) {
                    max = left;
                    reason = "今日限售";
                }
            }
            int have = TxEngine.count(player.getInventory(), entry.probe());
            if (have < max) {
                max = have;
                reason = "持有数量";
            }
        }
        return new Cap(Math.max(0, max), reason);
    }

    /**
     * Largest quantity the balance covers, fee included. Starts from a per-unit
     * estimate then walks down, because the fee rounds on the order total rather
     * than per unit.
     */
    private int affordable(long now, int ceiling) {
        long unit = plugin.market().buyUnit(entry, now);
        if (unit <= 0) {
            return ceiling;
        }
        long balance = plugin.data().loadSync(player.getUniqueId()).balance();
        long unitWithFee = Money.add(unit, fee(unit));
        int n = (int) Math.min(ceiling, balance / Math.max(1, unitWithFee));
        while (n > 0) {
            long gross = Money.times(unit, n);
            if (Money.add(gross, fee(gross)) <= balance) {
                break;
            }
            n--;
        }
        return n;
    }
}
