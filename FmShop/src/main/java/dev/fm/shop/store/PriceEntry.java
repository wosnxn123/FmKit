package dev.fm.shop.store;

import org.bukkit.Material;

/**
 * One immutable price-table row, as declared in config.yml.
 *
 * <p>Prices are per single item, in cents. A zero price disables that side of
 * the trade, so an item can be sellable-but-not-buyable (mob drops the shop
 * accepts but never hands out) or buyable-but-not-sellable (sinks).
 *
 * @param buy             cents the player pays per item, 0 = not for sale
 * @param sell            cents the player receives per item, 0 = not accepted
 * @param dailyBuy        per-player items purchasable per day, 0 = unlimited
 * @param dailySell       per-player items sellable per day, 0 = unlimited
 * @param dynamic         whether the market multiplier moves with trade volume
 * @param stepBp          multiplier change per item traded, in basis points
 * @param floorBp         lower bound of the multiplier (e.g. 5000 = 50%)
 * @param ceilBp          upper bound of the multiplier (e.g. 20000 = 200%)
 * @param recoverBpPerHour drift back toward 10000 per elapsed hour
 */
public record PriceEntry(Material material,
                         String category,
                         long buy,
                         long sell,
                         int dailyBuy,
                         int dailySell,
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
}
