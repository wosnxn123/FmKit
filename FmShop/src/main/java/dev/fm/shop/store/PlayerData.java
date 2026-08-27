package dev.fm.shop.store;

import dev.fm.shop.util.Money;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One player's shop state: balance plus today's per-item traded amounts.
 *
 * <p>All mutation goes through {@code synchronized} accessors. Buy and sell run
 * on the acting player's region thread while admin commands and the shutdown
 * flush run elsewhere, so an unguarded {@code balance += x} would lose credits
 * under a concurrent debit.
 */
public final class PlayerData {

    /** Balance in cents; see {@link Money}. */
    private long balance;
    /** Epoch day the counters below belong to; a different day resets them. */
    private long day;
    private final Map<Material, Integer> bought = new EnumMap<>(Material.class);
    private final Map<Material, Integer> sold = new EnumMap<>(Material.class);
    private long totalSpent;
    private long totalEarned;
    private boolean dirty;

    public synchronized long balance() {
        return balance;
    }

    /** Overwrite (admin set / load); returns the stored value. */
    public synchronized long balance(long cents) {
        balance = Math.max(0, Math.min(cents, Money.MAX));
        dirty = true;
        return balance;
    }

    /** Credit; saturates at the ceiling. */
    public synchronized long deposit(long cents) {
        if (cents <= 0) {
            return balance;
        }
        balance = Money.add(balance, cents);
        totalEarned = Money.add(totalEarned, cents);
        dirty = true;
        return balance;
    }

    /** Debit only when fully covered; false leaves the balance untouched. */
    public synchronized boolean withdraw(long cents) {
        if (cents < 0 || balance < cents) {
            return false;
        }
        balance -= cents;
        totalSpent = Money.add(totalSpent, cents);
        dirty = true;
        return true;
    }

    public synchronized long totalSpent() {
        return totalSpent;
    }

    public synchronized long totalEarned() {
        return totalEarned;
    }

    /** Amount of {@code mat} bought after rolling the day over if needed. */
    public synchronized int boughtToday(Material mat, long today) {
        roll(today);
        return bought.getOrDefault(mat, 0);
    }

    public synchronized int soldToday(Material mat, long today) {
        roll(today);
        return sold.getOrDefault(mat, 0);
    }

    public synchronized void addBought(Material mat, int n, long today) {
        roll(today);
        bought.merge(mat, n, Integer::sum);
        dirty = true;
    }

    public synchronized void addSold(Material mat, int n, long today) {
        roll(today);
        sold.merge(mat, n, Integer::sum);
        dirty = true;
    }

    /** Admin reset of one player's daily quotas. */
    public synchronized void resetQuotas() {
        bought.clear();
        sold.clear();
        dirty = true;
    }

    public synchronized long day() {
        return day;
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    public synchronized void clean() {
        dirty = false;
    }

    /** Snapshot for persistence: {@code MATERIAL -> amount}. */
    public synchronized Map<String, Integer> boughtSnapshot() {
        return snapshot(bought);
    }

    public synchronized Map<String, Integer> soldSnapshot() {
        return snapshot(sold);
    }

    /** Load path; bypasses the dirty flag. */
    public synchronized void restore(long balance, long day, long totalSpent, long totalEarned) {
        this.balance = balance;
        this.day = day;
        this.totalSpent = totalSpent;
        this.totalEarned = totalEarned;
    }

    public synchronized void restoreCounter(boolean buy, Material mat, int n) {
        (buy ? bought : sold).put(mat, n);
    }

    private void roll(long today) {
        if (day != today) {
            day = today;
            bought.clear();
            sold.clear();
            dirty = true;
        }
    }

    private static Map<String, Integer> snapshot(Map<Material, Integer> src) {
        Map<String, Integer> out = new LinkedHashMap<>(src.size() * 2);
        for (Map.Entry<Material, Integer> e : src.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                out.put(e.getKey().name(), e.getValue());
            }
        }
        return out;
    }
}
