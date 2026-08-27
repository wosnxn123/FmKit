package dev.fm.shop.economy;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.util.Money;

import java.util.UUID;

/**
 * The account layer: resolves a UUID to its {@link PlayerData} and persists
 * every mutation immediately.
 *
 * <p>Offline players are loaded synchronously on demand, which is why admin
 * commands and transfers are the only callers that may touch an unloaded
 * account; shop transactions always run against the acting player, whose data
 * was loaded on join.
 */
public final class Balances implements FmEconomy {

    private final FmShopPlugin plugin;

    public Balances(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public long balance(UUID player) {
        return data(player).balance();
    }

    @Override
    public boolean has(UUID player, long cents) {
        return cents <= 0 || data(player).balance() >= cents;
    }

    @Override
    public boolean withdraw(UUID player, long cents) {
        if (cents < 0) {
            return false;
        }
        PlayerData d = data(player);
        if (!d.withdraw(cents)) {
            return false;
        }
        plugin.data().save(player, d);
        return true;
    }

    @Override
    public boolean deposit(UUID player, long cents) {
        if (cents < 0) {
            return false;
        }
        PlayerData d = data(player);
        d.deposit(cents);
        plugin.data().save(player, d);
        return true;
    }

    @Override
    public long set(UUID player, long cents) {
        PlayerData d = data(player);
        long v = d.balance(cents);
        plugin.data().save(player, d);
        return v;
    }

    /**
     * Debit-then-credit under both account locks, taken in UUID order so two
     * players paying each other at the same time cannot deadlock. The credit
     * headroom is checked first: a saturating deposit into a maxed-out account
     * would otherwise destroy the debited amount.
     */
    @Override
    public boolean transfer(UUID from, UUID to, long cents) {
        if (cents <= 0 || from.equals(to)) {
            return false;
        }
        PlayerData a = data(from);
        PlayerData b = data(to);
        PlayerData first = from.compareTo(to) <= 0 ? a : b;
        PlayerData second = first == a ? b : a;
        boolean ok;
        synchronized (first) {
            synchronized (second) {
                if (b.balance() > Money.MAX - cents) {
                    return false;
                }
                ok = a.withdraw(cents);
                if (ok) {
                    b.deposit(cents);
                }
            }
        }
        if (ok) {
            plugin.data().save(from, a);
            plugin.data().save(to, b);
        }
        return ok;
    }

    @Override
    public String format(long cents) {
        return Money.format(cents, plugin.settings().currency());
    }

    @Override
    public String currency() {
        return plugin.settings().currency();
    }

    private PlayerData data(UUID player) {
        PlayerData d = plugin.data().get(player);
        return d != null ? d : plugin.data().loadSync(player);
    }
}
