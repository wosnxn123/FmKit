package dev.fm.shop.economy;

import java.util.UUID;

/**
 * FmShop's economy service, registered with the Bukkit {@code ServicesManager}
 * so sibling plugins can move money without touching FmShop's internals.
 *
 * <p>Every amount is in cents ({@code 100 = 1 coin}) and every mutating call is
 * atomic per account: a debit either fully succeeds or leaves the balance
 * untouched, so a caller can never half-charge a player.
 *
 * <p>Plugins that cannot see this interface (separate class loaders, no shared
 * dependency) should call the JDK-typed facade methods on {@code FmShopPlugin}
 * reflectively instead: {@code fmBalance(UUID)}, {@code fmWithdraw(UUID, long)},
 * {@code fmDeposit(UUID, long)}, {@code fmFormat(long)}.
 */
public interface FmEconomy {

    /** Balance in cents; 0 for an unknown player. */
    long balance(UUID player);

    /** True when the account covers {@code cents}. */
    boolean has(UUID player, long cents);

    /** Debits only when fully covered. */
    boolean withdraw(UUID player, long cents);

    /** Credits the account, saturating at the ceiling. */
    boolean deposit(UUID player, long cents);

    /** Overwrites the balance (admin). Returns the stored value. */
    long set(UUID player, long cents);

    /** Atomic player-to-player transfer. */
    boolean transfer(UUID from, UUID to, long cents);

    /** {@code 1,234.50 金币} using the configured currency name. */
    String format(long cents);

    /** Configured currency display name. */
    String currency();
}
