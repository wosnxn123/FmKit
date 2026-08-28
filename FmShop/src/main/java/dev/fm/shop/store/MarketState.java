package dev.fm.shop.store;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.util.Money;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-wide dynamic pricing: one multiplier per price row, in basis points.
 *
 * <p>Buying pushes an item's multiplier up, selling pushes it down, and it
 * drifts back toward 100% over time. Both directions are scaled by the SAME
 * multiplier on purpose: the configured sell/buy ratio - the spread that keeps
 * a buy-then-sell round trip lossy - is preserved at every multiplier value, so
 * dynamic pricing can never open a money loop. Independent buy and sell drift
 * would eventually cross.
 *
 * <p>Recovery is computed lazily on read from {@code lastMs}, so there is no
 * ticking task and an item nobody trades costs nothing to keep.
 */
public final class MarketState {

    /** 10000 bp = 100% = the configured price. */
    public static final int BASE_BP = 10_000;

    private static final class Row {
        int mulBp = BASE_BP;
        long lastMs;
        long boughtTotal;
        long soldTotal;
    }

    private final FmShopPlugin plugin;
    // Keyed by row id: two book rows share ENCHANTED_BOOK but trade
    // independently. Sized for traded rows only - most rows never move off 100%.
    private final Map<String, Row> rows = new HashMap<>(64);
    private final File file;
    private boolean dirty;

    public MarketState(FmShopPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
    }

    /** Current multiplier after applying time recovery. */
    public synchronized int multiplierBp(PriceEntry e, long nowMs) {
        if (!e.dynamic()) {
            return BASE_BP;
        }
        return settle(e, nowMs).mulBp;
    }

    /** Unit buy price in cents at the current multiplier; 0 when not for sale. */
    public synchronized long buyUnit(PriceEntry e, long nowMs) {
        return scale(e.buy(), multiplierBp(e, nowMs));
    }

    /** Unit sell price in cents at the current multiplier; 0 when not accepted. */
    public synchronized long sellUnit(PriceEntry e, long nowMs) {
        return scale(e.sell(), multiplierBp(e, nowMs));
    }

    /** Records a completed purchase of {@code qty} items. */
    public synchronized void onBuy(PriceEntry e, int qty, long nowMs) {
        Row r = settle(e, nowMs);
        r.boughtTotal += qty;
        if (e.dynamic() && e.stepBp() > 0) {
            long next = (long) r.mulBp + (long) e.stepBp() * qty;
            r.mulBp = (int) Math.min(e.ceilBp(), next);
        }
        dirty = true;
    }

    /** Records a completed sale of {@code qty} items. */
    public synchronized void onSell(PriceEntry e, int qty, long nowMs) {
        Row r = settle(e, nowMs);
        r.soldTotal += qty;
        if (e.dynamic() && e.stepBp() > 0) {
            long next = (long) r.mulBp - (long) e.stepBp() * qty;
            r.mulBp = (int) Math.max(e.floorBp(), next);
        }
        dirty = true;
    }

    /** Admin reset of one row's multiplier, or all of them when null. */
    public synchronized void reset(String id) {
        if (id == null) {
            rows.clear();
        } else {
            rows.remove(id);
        }
        dirty = true;
    }

    public synchronized long bought(String id) {
        Row r = rows.get(id);
        return r == null ? 0 : r.boughtTotal;
    }

    public synchronized long sold(String id) {
        Row r = rows.get(id);
        return r == null ? 0 : r.soldTotal;
    }

    /** Items currently away from 100%, for /fsa status. */
    public synchronized int movedCount() {
        int n = 0;
        for (Row r : rows.values()) {
            if (r.mulBp != BASE_BP) {
                n++;
            }
        }
        return n;
    }

    private Row settle(PriceEntry e, long nowMs) {
        Row r = rows.computeIfAbsent(e.id(), k -> {
            Row fresh = new Row();
            fresh.lastMs = nowMs;
            return fresh;
        });
        if (r.lastMs == 0) {
            r.lastMs = nowMs;
        }
        if (!e.dynamic() || e.recoverBpPerHour() <= 0 || r.mulBp == BASE_BP) {
            r.lastMs = nowMs;
            return r;
        }
        long elapsed = nowMs - r.lastMs;
        if (elapsed < 60_000L) {
            return r;
        }
        long recovered = e.recoverBpPerHour() * elapsed / 3_600_000L;
        if (recovered <= 0) {
            return r;
        }
        if (r.mulBp > BASE_BP) {
            r.mulBp = (int) Math.max(BASE_BP, r.mulBp - recovered);
        } else {
            r.mulBp = (int) Math.min(BASE_BP, r.mulBp + recovered);
        }
        r.lastMs = nowMs;
        dirty = true;
        return r;
    }

    /** price x multiplier, HALF_UP, never rounding a priced item down to free. */
    private static long scale(long cents, int mulBp) {
        if (cents <= 0) {
            return 0;
        }
        if (mulBp == BASE_BP) {
            return cents;
        }
        long q = cents / BASE_BP;
        long rem = cents % BASE_BP;
        if (q > Money.MAX / Math.max(1, mulBp)) {
            return Money.MAX;
        }
        long out = q * mulBp + (rem * mulBp + BASE_BP / 2) / BASE_BP;
        return Math.max(1, Math.min(Money.MAX, out));
    }

    public synchronized void load() {
        rows.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("market.yml 读取失败：" + ex.getMessage());
            return;
        }
        for (String key : y.getKeys(false)) {
            if (!y.isConfigurationSection(key)) {
                continue;
            }
            // Keys are row ids used verbatim. A plain row's id is its material
            // name, which is what old market files already store, so no key
            // migration is needed - and a book id (ENCHANTED_BOOK/SHARPNESS/5)
            // would not survive a Material lookup anyway.
            Row r = new Row();
            r.mulBp = Math.max(1, y.getInt(key + ".mul-bp", BASE_BP));
            r.lastMs = y.getLong(key + ".last-ms", System.currentTimeMillis());
            r.boughtTotal = Math.max(0, y.getLong(key + ".bought"));
            r.soldTotal = Math.max(0, y.getLong(key + ".sold"));
            rows.put(key, r);
        }
        dirty = false;
    }

    /** Snapshot for the IO thread; call on the caller's thread. */
    public synchronized YamlConfiguration snapshot() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Row> en : rows.entrySet()) {
            Row r = en.getValue();
            if (r.mulBp == BASE_BP && r.boughtTotal == 0 && r.soldTotal == 0) {
                continue;
            }
            String k = en.getKey();
            y.set(k + ".mul-bp", r.mulBp);
            y.set(k + ".last-ms", r.lastMs);
            y.set(k + ".bought", r.boughtTotal);
            y.set(k + ".sold", r.soldTotal);
        }
        dirty = false;
        return y;
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    /** Atomic write; safe to call from the IO thread with a prior snapshot. */
    public void write(YamlConfiguration y) {
        File tmp = new File(file.getParentFile(), "market.yml.tmp");
        try {
            y.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().warning("market.yml 保存失败：" + ex.getMessage());
        }
    }
}
