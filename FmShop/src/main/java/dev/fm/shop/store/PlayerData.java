package dev.fm.shop.store;

import dev.fm.shop.util.Money;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One player's shop state: balance, today's per-item traded amounts, and the
 * lifetime sell counts that drive unlock gating.
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
    /** Today's per-row counts, keyed by {@link PriceEntry#id()}. */
    private final Map<String, Integer> bought = new HashMap<>(8);
    private final Map<String, Integer> sold = new HashMap<>(8);
    /**
     * Lifetime sell counts keyed by {@link PriceEntry#id()}.
     *
     * <p>Unlike {@link #sold} this is never cleared by {@link #roll}: it is the
     * evidence that the player once obtained the item themselves, which is what
     * {@code unlock-by-sell} reads, and the ledger a {@code lifetime-sell} cap
     * counts against.
     */
    private final Map<String, Integer> soldEver = new HashMap<>(4);
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

    /** Amount of {@code id} bought after rolling the day over if needed. */
    public synchronized int boughtToday(String id, long today) {
        roll(today);
        return bought.getOrDefault(id, 0);
    }

    public synchronized int soldToday(String id, long today) {
        roll(today);
        return sold.getOrDefault(id, 0);
    }

    public synchronized void addBought(String id, int n, long today) {
        roll(today);
        bought.merge(id, n, Integer::sum);
        dirty = true;
    }

    public synchronized void addSold(String id, int n, long today) {
        roll(today);
        sold.merge(id, n, Integer::sum);
        dirty = true;
    }

    /** Lifetime count of {@code id} sold; day-independent. */
    public synchronized int soldEver(String id) {
        return soldEver.getOrDefault(id, 0);
    }

    /** Whether the player has ever sold {@code id}, i.e. proved ownership. */
    public synchronized boolean unlocked(String id) {
        return soldEver.getOrDefault(id, 0) > 0;
    }

    public synchronized void addSoldEver(String id, int n) {
        if (n <= 0) {
            return;
        }
        soldEver.merge(id, n, Integer::sum);
        dirty = true;
    }

    /** Admin reset of one player's daily quotas. */
    public synchronized void resetQuotas() {
        bought.clear();
        sold.clear();
        dirty = true;
    }

    /**
     * Admin clear of one player's lifetime counters, which re-locks every
     * unlock-gated row and refunds their lifetime sell allowance.
     *
     * <p>Separate from {@link #resetQuotas} on purpose: a daily-limit reset is
     * routine, whereas this rewrites history and is the only way back from a
     * mis-configured cap.
     */
    public synchronized void resetLifetime() {
        soldEver.clear();
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

    /** Snapshot for persistence: {@code id -> amount}. */
    public synchronized Map<String, Integer> boughtSnapshot() {
        return snapshot(bought);
    }

    public synchronized Map<String, Integer> soldSnapshot() {
        return snapshot(sold);
    }

    /** Snapshot for persistence: {@code id -> lifetime amount}. */
    public synchronized Map<String, Integer> soldEverSnapshot() {
        return snapshot(soldEver);
    }

    /** Load path; bypasses the dirty flag. */
    public synchronized void restore(long balance, long day, long totalSpent, long totalEarned) {
        this.balance = balance;
        this.day = day;
        this.totalSpent = totalSpent;
        this.totalEarned = totalEarned;
    }

    public synchronized void restoreCounter(boolean buy, String id, int n) {
        (buy ? bought : sold).put(id, n);
    }

    public synchronized void restoreSoldEver(String id, int n) {
        soldEver.put(id, n);
    }

    /**
     * Rolls the daily counters when the calendar day changed.
     *
     * <p>{@link #soldEver} is deliberately untouched: it is a lifetime ledger,
     * and clearing it here would silently re-lock every unlock-gated row every
     * midnight and hand back every lifetime allowance.
     */
    private void roll(long today) {
        if (day != today) {
            day = today;
            bought.clear();
            sold.clear();
            dirty = true;
        }
    }

    private static Map<String, Integer> snapshot(Map<String, Integer> src) {
        Map<String, Integer> out = new LinkedHashMap<>(src.size() * 2);
        for (Map.Entry<String, Integer> e : src.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
