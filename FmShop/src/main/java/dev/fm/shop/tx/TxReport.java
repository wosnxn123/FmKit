package dev.fm.shop.tx;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.util.ItemNames;
import dev.fm.shop.util.Money;
import dev.fm.shop.util.TextUtil;
import dev.fm.shop.util.TimeUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Turns a {@link TxResult} into the one chat line the player should see.
 *
 * <p>{@code TxEngine} deliberately returns data instead of talking, so the same
 * transaction reads identically whether it came from a command or a menu click.
 * All wording lives in config; this only picks the key and fills placeholders.
 */
public final class TxReport {

    private TxReport() {
    }

    public static void tell(FmShopPlugin plugin, Player p, Material mat, TxResult r) {
        String item = mat == null ? "" : ItemNames.mini(mat);
        String line = switch (r.key()) {
            case "buy-ok" -> TextUtil.apply(plugin.settings().msg("buy-ok"),
                    "item", item, "n", String.valueOf(r.qty()), "cost", money(plugin, r.net()));
            case "sell-ok" -> TextUtil.apply(plugin.settings().msg("sell-ok"),
                    "item", item, "n", String.valueOf(r.qty()), "gain", money(plugin, r.net()))
                    + (r.fee() > 0
                    ? TextUtil.apply(plugin.settings().msg("sell-fee"), "fee", money(plugin, r.fee()))
                    : "");
            case "no-money" -> TextUtil.apply(plugin.settings().msg("no-money"),
                    "need", money(plugin, r.need()));
            case "quota-buy", "quota-sell" -> TextUtil.apply(plugin.settings().msg(r.key()),
                    "item", item,
                    "limit", String.valueOf(r.limit()),
                    "left", String.valueOf(r.left()),
                    "reset", TimeUtil.format(plugin.settings().untilReset()));
            default -> TextUtil.apply(plugin.settings().msg(r.key()), "item", item);
        };
        if (!line.isEmpty()) {
            TextUtil.msg(p, plugin.settings().msg("prefix") + line);
        }
    }

    /** Sell-all reports once for the whole sweep rather than per material. */
    public static void tellSweep(FmShopPlugin plugin, Player p, TxEngine.Sweep s) {
        if (s.empty()) {
            TextUtil.msg(p, plugin.settings().prefixed("nothing-to-sell"));
            return;
        }
        String line = TextUtil.apply(plugin.settings().msg("sell-all-ok"),
                "kinds", String.valueOf(s.kinds()),
                "n", String.valueOf(s.items()),
                "gain", money(plugin, s.net()));
        if (s.fee() > 0) {
            line += TextUtil.apply(plugin.settings().msg("sell-fee"), "fee", money(plugin, s.fee()));
        }
        TextUtil.msg(p, plugin.settings().msg("prefix") + line);
    }

    private static String money(FmShopPlugin plugin, long cents) {
        return Money.format(cents, plugin.settings().currency());
    }
}
