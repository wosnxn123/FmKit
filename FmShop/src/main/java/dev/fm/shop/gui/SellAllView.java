package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.tx.TxEngine;
import dev.fm.shop.tx.TxReport;
import dev.fm.shop.util.Money;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preview of a bulk sell: every priced stack in the inventory, what it pays, and
 * one button to commit.
 *
 * <p>The preview applies the same per-row clamps the engine will
 * ({@code max-per-action}, daily quota), so the total shown is the total paid
 * unless prices move between preview and click.
 */
public final class SellAllView extends View {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_TOTAL = 49;
    private static final int SLOT_CONFIRM = 53;

    private record Line(PriceEntry entry, int qty, long gross, long fee) {
    }

    private final List<Line> lines = new ArrayList<>();
    private long gross;
    private long fee;

    public SellAllView(FmShopPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected Component title() {
        return Icons.plain(plugin.settings().msg("gui-title-bag"));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    public void render() {
        scan();
        String cur = plugin.settings().currency();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            if (slot >= lines.size()) {
                getInventory().setItem(slot, null);
                continue;
            }
            Line l = lines.get(slot);
            long net = l.gross() - l.fee();
            getInventory().setItem(slot, Icons.of(l.entry().probe(), l.qty(),
                    "<white>" + l.entry().key().mini() + " <gray>×<white>" + l.qty(),
                    List.of("<gold>可得 <white>" + Money.format(net, cur),
                            "<gray>单价 <white>" + Money.format(l.gross() / Math.max(1, l.qty()), cur))));
        }
        Gui.fillRow(plugin, getInventory(), 45);
        getInventory().setItem(SLOT_BACK, Icons.of(plugin.settings().icon("back", Material.ARROW),
                "<white>返回商店"));
        long net = gross - fee;
        List<String> lore = new ArrayList<>(5);
        lore.add("<gray>物品种类 <white>" + lines.size());
        lore.add("<gray>合计 <white>" + Money.format(gross, cur));
        if (fee > 0) {
            lore.add("<gray>手续费 <white>" + Money.format(fee, cur));
        }
        lore.add("<gold>预计可得 <white>" + Money.format(net, cur));
        getInventory().setItem(SLOT_TOTAL, Icons.of(plugin.settings().icon("bag", Material.CHEST),
                "<aqua>回收预览", lore));
        getInventory().setItem(SLOT_CONFIRM, lines.isEmpty()
                ? Icons.of(plugin.settings().icon("cancel", Material.RED_DYE),
                "<red>没有可回收的物品",
                List.of("<gray>快捷栏与盔甲栏之外的物品也会被扫描"))
                : Icons.of(plugin.settings().icon("confirm", Material.LIME_DYE),
                "<green>确认全部回收",
                List.of("<gray>卖出 <white>" + lines.size() + "</white> 种共 <white>"
                                + lines.stream().mapToInt(Line::qty).sum() + "</white> 件",
                        "<gold>可得 <white>" + Money.format(net, cur))));
    }

    @Override
    public void click(int slot, ClickType type) {
        if (slot == SLOT_BACK) {
            Gui.click(plugin, player);
            new HubView(plugin, player).open();
            return;
        }
        if (slot != SLOT_CONFIRM) {
            return;
        }
        if (lines.isEmpty()) {
            Gui.deny(plugin, player);
            return;
        }
        TxEngine.Sweep s = plugin.tx().sellAll(player);
        TxReport.tellSweep(plugin, player, s);
        if (s.empty()) {
            Gui.deny(plugin, player);
        }
        if (live()) {
            render();
        }
    }

    /** Groups the inventory into per-row sell lines with fees applied. */
    private void scan() {
        lines.clear();
        gross = 0;
        fee = 0;
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        // Dedupe by row, not material: two enchanted books are the same material
        // but different rows, and match() already rejects unexpected NBT.
        Map<PriceEntry, Integer> held = new LinkedHashMap<>();
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            PriceEntry e = plugin.prices().match(it);
            if (e == null || !e.sellable()) {
                continue;
            }
            held.merge(e, it.getAmount(), Integer::sum);
        }
        long now = System.currentTimeMillis();
        long today = plugin.settings().today();
        PlayerData d = plugin.data().loadSync(player.getUniqueId());
        boolean exempt = player.hasPermission(TxEngine.FEE_EXEMPT);
        int bp = plugin.settings().sellFeeBp();
        for (Map.Entry<PriceEntry, Integer> en : held.entrySet()) {
            PriceEntry e = en.getKey();
            int qty = Math.min(en.getValue(), plugin.settings().maxPerAction());
            if (e.dailySell() > 0) {
                qty = Math.min(qty, Math.max(0, e.dailySell() - d.soldToday(e.id(), today)));
            }
            if (qty <= 0) {
                continue;
            }
            long g = Money.times(plugin.market().sellUnit(e, now), qty);
            long f = exempt ? 0 : Money.basisPoints(g, bp);
            lines.add(new Line(e, qty, g, f));
            gross = Money.add(gross, g);
            fee = Money.add(fee, f);
        }
    }
}
