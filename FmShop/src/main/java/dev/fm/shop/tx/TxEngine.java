package dev.fm.shop.tx;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.Settings;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.util.Money;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.LinkedHashMap;
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
 * <p>Sale offers are matched against the row's probe stack via
 * {@link ItemStack#isSimilar}, not by material: a renamed, damaged or wrongly
 * enchanted stack matches no row and cannot be sold. That keeps an item worth
 * more than its row price out of the sell path - buying it at that rate would
 * rob the player and hand griefers a laundering channel.
 *
 * <p>Two per-player gates ride on the lifetime sell ledger. A row marked
 * {@code unlock-by-sell} refuses to sell to a player who has never sold one,
 * and a row with a {@code lifetime-sell} cap stops accepting once that cap is
 * reached. The ledger is only written for rows that declare one of those, so a
 * plain row costs nothing on disk - the flip side being that adding the flag to
 * a row later re-locks it for everyone, since their earlier sales were never
 * recorded.
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

        if (e.unlockBySell() && !d.unlocked(e.id())) {
            return TxResult.fail("locked");
        }

        if (e.dailyBuy() > 0) {
            int left = e.dailyBuy() - d.boughtToday(e.id(), today);
            if (left <= 0) {
                return TxResult.quota("quota-buy", e.dailyBuy(), 0);
            }
            qty = Math.min(qty, left);
        }
        int room = space(p.getInventory(), e.probe());
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
        give(p, e.probe(), qty);
        d.addBought(e.id(), qty, today);
        plugin.data().save(p.getUniqueId(), d);
        plugin.market().onBuy(e, qty, now);
        collect(fee);
        plugin.audit().log(p, "BUY", e.key(), qty, gross, fee, total, d.balance());
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
        ItemStack probe = e.probe();
        int have = count(p.getInventory(), probe);
        if (have <= 0) {
            return TxResult.fail("nothing-to-sell");
        }
        int qty = Math.min(Math.min(Math.max(1, want), s.maxPerAction()), have);
        PlayerData d = plugin.data().loadSync(p.getUniqueId());
        long today = s.today();

        if (e.dailySell() > 0) {
            int left = e.dailySell() - d.soldToday(e.id(), today);
            if (left <= 0) {
                return TxResult.quota("quota-sell", e.dailySell(), 0);
            }
            qty = Math.min(qty, left);
        }
        if (e.lifetimeSell() > 0) {
            int left = e.lifetimeSell() - d.soldEver(e.id());
            if (left <= 0) {
                return TxResult.quota("quota-lifetime", e.lifetimeSell(), 0);
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
        d.addSold(e.id(), taken, today);
        if (e.unlockBySell() || e.lifetimeSell() > 0) {
            d.addSoldEver(e.id(), taken);
        }
        plugin.data().save(p.getUniqueId(), d);
        plugin.market().onSell(e, taken, now);
        collect(fee);
        plugin.audit().log(p, "SELL", e.key(), taken, gross, fee, net, d.balance());
        play(p, false);
        return TxResult.done("sell-ok", taken, gross, fee, net);
    }

    /**
     * Sells every priced stack in the player's inventory.
     *
     * <p>Each row is settled independently so one quota-blocked row does
     * not abort the rest of the sweep.
     */
    public Sweep sellAll(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE) {
            return new Sweep(0, 0, 0, 0, 0);
        }
        // Resolve through match(ItemStack), never by material: an unenchanted
        // book and a Sharpness V book share Material.ENCHANTED_BOOK but are
        // different rows, and a stack with unexpected NBT must resolve to no
        // row at all rather than fall back to the plain row.
        Map<String, PriceEntry> present = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            PriceEntry e = plugin.prices().match(it);
            if (e != null && e.sellable()) {
                present.putIfAbsent(e.id(), e);
            }
        }
        int kinds = 0;
        int items = 0;
        long gross = 0;
        long fee = 0;
        long net = 0;
        for (PriceEntry e : present.values()) {
            TxResult r = sell(p, e, plugin.settings().maxPerAction());
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

    /** How many more of the probe stack the storage contents can hold. */
    public static int space(PlayerInventory inv, ItemStack probe) {
        int emptyStack = probe.getMaxStackSize();
        int free = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                free += emptyStack;
            } else if (it.isSimilar(probe)) {
                free += Math.max(0, probe.getMaxStackSize() - it.getAmount());
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

    private static void give(Player p, ItemStack probe, int qty) {
        int left = qty;
        int max = probe.getMaxStackSize();
        while (left > 0) {
            int n = Math.min(max, left);
            left -= n;
            // Clone per stack: addItem mutates what it is handed, and callers
            // reuse their probe across a multi-stack give.
            ItemStack stack = probe.clone();
            stack.setAmount(n);
            Map<Integer, ItemStack> spill = p.getInventory().addItem(stack);
            for (ItemStack rest : spill.values()) {
                // Pre-checked space makes this unreachable in practice; dropping
                // beats silently deleting an item the player already paid for.
                p.getWorld().dropItem(p.getLocation(), rest);
            }
        }
    }
}
