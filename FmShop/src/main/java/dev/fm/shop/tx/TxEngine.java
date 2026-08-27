package dev.fm.shop.tx;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.Settings;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.util.Money;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every money-for-items move in FmShop goes through here.
 *
 * <p>Ordering is the whole point of this class: an order is clamped to what is
 * actually possible (quota, inventory space, stock in the bag) BEFORE any money
 * or item moves, then the balance is debited, and only then are items handed
 * over. A failure at any earlier step leaves both sides untouched, so no path
 * can charge a player without delivering or delete items without paying.
 *
 * <p>Only "plain" stacks are accepted for sale - {@link ItemStack#isSimilar}
 * against a bare stack of the material. An enchanted, renamed, damaged or
 * otherwise NBT-bearing item is worth more than its material price, so buying
 * it at the material rate would rob the player and hand griefers a laundering
 * channel.
 *
 * <p>Runs on the caller's thread, which for commands and GUI clicks is the
 * acting player's region thread: inventory access is therefore always local.
 */
public final class TxEngine {

    /** Aggregate of a multi-item sweep (sell-all). */
    public record Sweep(int kinds, int items, long gross, long fee, long net) {
        public boolean empty() {
            return items == 0;
        }
    }

    /** Bypasses buy/sell/pay fees; also used by menus to preview true totals. */
    public static final String FEE_EXEMPT = "fmshop.fee.exempt";

    private final FmShopPlugin plugin;

    public TxEngine(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ buy

    public TxResult buy(Player p, PriceEntry e, int want) {
        if (!e.buyable()) {
            return TxResult.fail("not-buyable");
        }
        Settings s = plugin.settings();
        int qty = Math.min(Math.max(1, want), s.maxPerAction());
        PlayerData d = plugin.data().loadSync(p.getUniqueId());
        long today = s.today();

        if (e.dailyBuy() > 0) {
            int left = e.dailyBuy() - d.boughtToday(e.material(), today);
            if (left <= 0) {
                return TxResult.quota("quota-buy", e.dailyBuy(), 0);
            }
            qty = Math.min(qty, left);
        }
        int room = space(p.getInventory(), e.material());
        if (room <= 0) {
            return TxResult.fail("no-space");
        }
        qty = Math.min(qty, room);

        long now = System.currentTimeMillis();
        long gross = Money.times(plugin.market().buyUnit(e, now), qty);
        long fee = p.hasPermission(FEE_EXEMPT) ? 0 : Money.basisPoints(gross, s.buyFeeBp());
        long total = Money.add(gross, fee);

        if (!d.withdraw(total)) {
            return TxResult.poor(total - d.balance());
        }
        give(p, e.material(), qty);
        d.addBought(e.material(), qty, today);
        plugin.data().save(p.getUniqueId(), d);
        plugin.market().onBuy(e, qty, now);
        collect(fee);
        plugin.audit().log(p, "BUY", e.material(), qty, gross, fee, total, d.balance());
        play(p, true);
        return TxResult.done("buy-ok", qty, gross, fee, total);
    }

    // ----------------------------------------------------------------- sell

    public TxResult sell(Player p, PriceEntry e, int want) {
        if (!e.sellable()) {
            return TxResult.fail("not-sellable");
        }
        if (p.getGameMode() == GameMode.CREATIVE) {
            return TxResult.fail("no-sell-creative");
        }
        Settings s = plugin.settings();
        ItemStack probe = new ItemStack(e.material());
        int have = count(p.getInventory(), probe);
        if (have <= 0) {
            return TxResult.fail("nothing-to-sell");
        }
        int qty = Math.min(Math.min(Math.max(1, want), s.maxPerAction()), have);
        PlayerData d = plugin.data().loadSync(p.getUniqueId());
        long today = s.today();

        if (e.dailySell() > 0) {
            int left = e.dailySell() - d.soldToday(e.material(), today);
            if (left <= 0) {
                return TxResult.quota("quota-sell", e.dailySell(), 0);
            }
            qty = Math.min(qty, left);
        }
        int taken = take(p.getInventory(), probe, qty);
        if (taken <= 0) {
            return TxResult.fail("nothing-to-sell");
        }
        long now = System.currentTimeMillis();
        long gross = Money.times(plugin.market().sellUnit(e, now), taken);
        long fee = p.hasPermission(FEE_EXEMPT) ? 0 : Money.basisPoints(gross, s.sellFeeBp());
        long net = gross - fee;

        d.deposit(net);
        d.addSold(e.material(), taken, today);
        plugin.data().save(p.getUniqueId(), d);
        plugin.market().onSell(e, taken, now);
        collect(fee);
        plugin.audit().log(p, "SELL", e.material(), taken, gross, fee, net, d.balance());
        play(p, false);
        return TxResult.done("sell-ok", taken, gross, fee, net);
    }

    /**
     * Sells every priced, plain stack in the player's inventory.
     *
     * <p>Each material is settled independently so one quota-blocked item does
     * not abort the rest of the sweep.
     */
    public Sweep sellAll(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE) {
            return new Sweep(0, 0, 0, 0, 0);
        }
        List<Material> present = new ArrayList<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            PriceEntry e = plugin.prices().get(it.getType());
            if (e != null && e.sellable() && !present.contains(it.getType())) {
                present.add(it.getType());
            }
        }
        int kinds = 0;
        int items = 0;
        long gross = 0;
        long fee = 0;
        long net = 0;
        for (Material mat : present) {
            TxResult r = sell(p, plugin.prices().get(mat), plugin.settings().maxPerAction());
            if (r.ok()) {
                kinds++;
                items += r.qty();
                gross += r.gross();
                fee += r.fee();
                net += r.net();
            }
        }
        return new Sweep(kinds, items, gross, fee, net);
    }

    // ------------------------------------------------------------------ pay

    /** Player-to-player transfer with the configured fee taken from the sender. */
    public TxResult pay(Player from, UUID to, long cents) {
        Settings s = plugin.settings();
        if (cents < s.minPay()) {
            return TxResult.fail("pay-too-small");
        }
        if (from.getUniqueId().equals(to)) {
            return TxResult.fail("pay-self");
        }
        long fee = from.hasPermission(FEE_EXEMPT) ? 0 : Money.basisPoints(cents, s.payFeeBp());
        long total = Money.add(cents, fee);
        UUID payer = from.getUniqueId();
        if (!plugin.economy().has(payer, total)) {
            return TxResult.poor(total - plugin.economy().balance(payer));
        }
        // Fee first: if the transfer then fails (recipient at the balance
        // ceiling) the fee is refunded, so no path can take money without
        // moving the principal.
        if (fee > 0 && !plugin.economy().withdraw(payer, fee)) {
            return TxResult.poor(total - plugin.economy().balance(payer));
        }
        if (!plugin.economy().transfer(payer, to, cents)) {
            if (fee > 0) {
                plugin.economy().deposit(payer, fee);
            }
            return TxResult.fail("pay-failed");
        }
        collect(fee);
        plugin.audit().log(from, "PAY", null, 1, cents, fee, total,
                plugin.economy().balance(from.getUniqueId()));
        return TxResult.done("pay-ok", 1, cents, fee, total);
    }

    // -------------------------------------------------------------- helpers

    private void collect(long fee) {
        if (fee > 0 && plugin.settings().feeSink() == Settings.FeeSink.TAX_POOL) {
            plugin.tax().add(fee);
        }
    }

    private void play(Player p, boolean buy) {
        if (plugin.settings().sounds()) {
            p.playSound(p.getLocation(),
                    buy ? Sound.ENTITY_ITEM_PICKUP : Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                    0.7f, buy ? 1.0f : 1.4f);
        }
    }

    /** How many more of {@code mat} the storage contents can hold. */
    public static int space(PlayerInventory inv, Material mat) {
        ItemStack probe = new ItemStack(mat);
        int emptyStack = probe.getMaxStackSize();
        int free = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                free += emptyStack;
            } else if (it.isSimilar(probe)) {
                free += Math.max(0, it.getMaxStackSize() - it.getAmount());
            }
            if (free >= 4096) {
                break;
            }
        }
        return free;
    }

    /** Plain stacks only; enchanted/renamed/damaged copies are not counted. */
    public static int count(PlayerInventory inv, ItemStack probe) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it != null && it.isSimilar(probe)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private static int take(PlayerInventory inv, ItemStack probe, int qty) {
        ItemStack[] contents = inv.getStorageContents();
        int left = qty;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(probe)) {
                continue;
            }
            int use = Math.min(left, it.getAmount());
            left -= use;
            if (use == it.getAmount()) {
                contents[i] = null;
            } else {
                it.setAmount(it.getAmount() - use);
            }
        }
        if (left < qty) {
            inv.setStorageContents(contents);
        }
        return qty - left;
    }

    private static void give(Player p, Material mat, int qty) {
        int left = qty;
        int max = new ItemStack(mat).getMaxStackSize();
        while (left > 0) {
            int n = Math.min(max, left);
            left -= n;
            Map<Integer, ItemStack> spill = p.getInventory().addItem(new ItemStack(mat, n));
            for (ItemStack rest : spill.values()) {
                // Pre-checked space makes this unreachable in practice; dropping
                // beats silently deleting an item the player already paid for.
                p.getWorld().dropItem(p.getLocation(), rest);
            }
        }
    }
}
