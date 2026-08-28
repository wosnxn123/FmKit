package dev.fm.shop.store;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * One immutable price-table row, as declared in prices.yml.
 *
 * <p>Prices are per single item, in cents. A zero price disables that side of
 * the trade, so an item can be sellable-but-not-buyable (mob drops the shop
 * accepts but never hands out) or buyable-but-not-sellable (sinks).
 *
 * <p>{@code unlockBySell} gates the buy side behind proof of ownership: until
 * the player has sold at least one, the row is not purchasable. That keeps the
 * gameplay that produces an item alive - a shop that sells enchanted books over
 * the counter deletes librarian breeding as content - while still allowing the
 * convenience purchase once the player has done it the real way.
 *
 * <p>{@code lifetimeSell} is a per-player cap that never resets, unlike
 * {@link #dailySell}. It is what makes the unlock safe to pair with a real sell
 * price: a renewable farm can cash in once, not forever.
 *
 * @param key              what the row trades; see {@link ItemKey}
 * @param category         tab id this row appears under
 * @param buy              cents the player pays per item, 0 = not for sale
 * @param sell             cents the player receives per item, 0 = not accepted
 * @param dailyBuy         per-player items purchasable per day, 0 = unlimited
 * @param dailySell        per-player items sellable per day, 0 = unlimited
 * @param unlockBySell     buy side stays locked until the player has sold one
 * @param lifetimeSell     per-player items sellable ever, 0 = unlimited
 * @param dynamic          whether the market multiplier moves with trade volume
 * @param stepBp           multiplier change per item traded, in basis points
 * @param floorBp          lower bound of the multiplier (e.g. 5000 = 50%)
 * @param ceilBp           upper bound of the multiplier (e.g. 20000 = 200%)
 * @param recoverBpPerHour drift back toward 10000 per elapsed hour
 */
public record PriceEntry(ItemKey key,
                         String category,
                         long buy,
                         long sell,
                         int dailyBuy,
                         int dailySell,
                         boolean unlockBySell,
                         int lifetimeSell,
                         boolean dynamic,
                         int stepBp,
                         int floorBp,
                         int ceilBp,
                         int recoverBpPerHour) {

    public boolean buyable() {
        return buy > 0;
    }

    public boolean sellable() {
        return sell > 0;
    }

    /** Persistence and lookup key for this row. */
    public String id() {
        return key.id();
    }

    public Material material() {
        return key.material();
    }

    /** A one-item stack of exactly what this row trades. */
    public ItemStack probe() {
        return key.probe();
    }
}
