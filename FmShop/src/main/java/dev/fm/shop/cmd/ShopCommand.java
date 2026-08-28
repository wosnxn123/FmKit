package dev.fm.shop.cmd;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.store.ItemKey;
import dev.fm.shop.store.MarketState;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.tx.TxReport;
import dev.fm.shop.tx.TxResult;
import dev.fm.shop.util.Money;
import dev.fm.shop.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /fmshop} - the player-facing surface.
 *
 * <p>Everything here is a thin shell over {@code TxEngine}: parse, resolve the
 * item, hand off, report. Prices, fees, quotas and clamping all stay in the
 * engine so the menu and the command can never disagree about what a trade costs.
 */
public final class ShopCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("buy", "sell", "price", "balance", "pay", "help");

    private final FmShopPlugin plugin;

    public ShopCommand(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            Player p = player(sender);
            if (p != null) {
                plugin.gui().openHub(p);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(sender, label);
            case "balance", "bal", "money" -> balance(sender, args);
            case "price", "p" -> price(sender, args);
            case "buy", "b" -> buy(sender, args);
            case "sell", "s" -> sell(sender, args);
            case "pay" -> pay(sender, args);
            // No message key for this: an unknown subcommand is a typo, and the
            // help list is more useful than a one-line scold.
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        TextUtil.send(sender, "<gold>商店指令");
        TextUtil.send(sender, "<yellow>/" + label + " <gray>打开商店界面");
        TextUtil.send(sender, "<yellow>/" + label + " buy <物品> [数量] <gray>购买");
        TextUtil.send(sender, "<yellow>/" + label + " sell <物品|hand|inv> [数量] <gray>出售");
        TextUtil.send(sender, "<yellow>/" + label + " price <物品> <gray>查询价格");
        TextUtil.send(sender, "<yellow>/" + label + " balance <gray>查询余额");
        if (sender.hasPermission("fmshop.pay")) {
            TextUtil.send(sender, "<yellow>/" + label + " pay <玩家> <金额> <gray>转账");
        }
    }

    private void balance(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!sender.hasPermission("fmshop.admin")) {
                TextUtil.send(sender, plugin.settings().prefixed("no-permission"));
                return;
            }
            OfflinePlayer target = lookup(args[1]);
            if (target == null) {
                TextUtil.send(sender, plugin.settings().msg("prefix")
                        + TextUtil.apply(plugin.settings().msg("player-not-found"), "player", args[1]));
                return;
            }
            TextUtil.send(sender, plugin.settings().msg("prefix")
                    + TextUtil.apply(plugin.settings().msg("balance-other"),
                    "player", String.valueOf(target.getName()),
                    "amount", money(plugin.economy().balance(target.getUniqueId()))));
            return;
        }
        Player p = player(sender);
        if (p == null) {
            return;
        }
        TextUtil.send(sender, plugin.settings().msg("prefix")
                + TextUtil.apply(plugin.settings().msg("balance"),
                "amount", money(plugin.economy().balance(p.getUniqueId()))));
    }

    private void price(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TextUtil.send(sender, "<red>用法：/fmshop price <物品>");
            return;
        }
        PriceEntry e = resolve(sender, args[1]);
        if (e == null) {
            return;
        }
        long now = System.currentTimeMillis();
        TextUtil.send(sender, plugin.settings().msg("prefix")
                + TextUtil.apply(plugin.settings().msg("price-line"),
                "item", e.key().mini(),
                "buy", e.buyable() ? money(plugin.market().buyUnit(e, now)) : "-",
                "sell", e.sellable() ? money(plugin.market().sellUnit(e, now)) : "-"));
        if (e.dynamic()) {
            int bp = plugin.market().multiplierBp(e, now);
            TextUtil.send(sender, TextUtil.apply(plugin.settings().msg("price-dynamic"),
                    "percent", String.valueOf(Math.round(bp / 100f)),
                    "trend", bp > MarketState.BASE_BP ? "<red>走高"
                            : bp < MarketState.BASE_BP ? "<green>走低" : "<gray>持平"));
        }
    }

    private void buy(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null) {
            return;
        }
        if (args.length < 2) {
            TextUtil.send(sender, "<red>用法：/fmshop buy <物品> [数量]");
            return;
        }
        PriceEntry e = resolve(sender, args[1]);
        if (e == null) {
            return;
        }
        TxResult r = plugin.tx().buy(p, e, qty(args, 2));
        TxReport.tell(plugin, p, e.key(), r);
    }

    private void sell(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null) {
            return;
        }
        if (args.length < 2) {
            TextUtil.send(sender, "<red>用法：/fmshop sell <物品|hand|inv> [数量]");
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        if (what.equals("inv") || what.equals("all")) {
            // sellAll() reports a creative-mode refusal as an empty sweep, which
            // would read as "nothing to sell"; name the real reason here.
            if (p.getGameMode() == GameMode.CREATIVE) {
                TextUtil.send(sender, plugin.settings().prefixed("no-sell-creative"));
                return;
            }
            TxReport.tellSweep(plugin, p, plugin.tx().sellAll(p));
            return;
        }
        if (what.equals("hand")) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                TextUtil.send(sender, plugin.settings().prefixed("hand-empty"));
                return;
            }
            PriceEntry e = plugin.prices().match(hand);
            if (e == null || !e.sellable()) {
                TextUtil.send(sender, plugin.settings().msg("prefix")
                        + TextUtil.apply(plugin.settings().msg("not-sellable"),
                        "item", ItemKey.of(hand.getType()).mini()));
                return;
            }
            TxResult r = plugin.tx().sell(p, e, hand.getAmount());
            TxReport.tell(plugin, p, e.key(), r);
            return;
        }
        PriceEntry e = resolve(sender, args[1]);
        if (e == null) {
            return;
        }
        TxResult r = plugin.tx().sell(p, e, qty(args, 2));
        TxReport.tell(plugin, p, e.key(), r);
    }

    private void pay(CommandSender sender, String[] args) {
        Player p = player(sender);
        if (p == null) {
            return;
        }
        if (!p.hasPermission("fmshop.pay")) {
            TextUtil.send(sender, plugin.settings().prefixed("no-permission"));
            return;
        }
        if (args.length < 3) {
            TextUtil.send(sender, "<red>用法：/fmshop pay <玩家> <金额>");
            return;
        }
        OfflinePlayer target = lookup(args[1]);
        if (target == null) {
            TextUtil.send(sender, plugin.settings().msg("prefix")
                    + TextUtil.apply(plugin.settings().msg("player-not-found"), "player", args[1]));
            return;
        }
        long cents = Money.parse(args[2]);
        if (cents <= 0) {
            TextUtil.send(sender, plugin.settings().msg("prefix")
                    + TextUtil.apply(plugin.settings().msg("bad-amount"), "input", args[2]));
            return;
        }
        UUID to = target.getUniqueId();
        TxResult r = plugin.tx().pay(p, to, cents);
        if (!r.ok()) {
            if (r.key().equals("pay-too-small")) {
                TextUtil.send(sender, plugin.settings().msg("prefix")
                        + TextUtil.apply(plugin.settings().msg("pay-too-small"),
                        "min", money(plugin.settings().minPay())));
            } else {
                TxReport.tell(plugin, p, null, r);
            }
            return;
        }
        TextUtil.send(sender, plugin.settings().msg("prefix")
                + TextUtil.apply(plugin.settings().msg("pay-ok"),
                "player", String.valueOf(target.getName()),
                "amount", money(r.gross())));
        Player online = Bukkit.getPlayer(to);
        if (online != null) {
            TextUtil.msg(online, plugin.settings().msg("prefix")
                    + TextUtil.apply(plugin.settings().msg("pay-recv"),
                    "player", p.getName(),
                    "amount", money(r.gross())));
        }
    }

    // -------------------------------------------------------------- helpers

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        TextUtil.send(sender, plugin.settings().prefixed("players-only"));
        return null;
    }

    /** Resolves user input to a priced item, reporting the failure itself. */
    private PriceEntry resolve(CommandSender sender, String input) {
        PriceEntry e = plugin.prices().match(input);
        if (e == null) {
            TextUtil.send(sender, plugin.settings().msg("prefix")
                    + TextUtil.apply(plugin.settings().msg("unknown-item"), "input", input));
        }
        return e;
    }

    private static int qty(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[index]));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private String money(long cents) {
        return Money.format(cents, plugin.settings().currency());
    }

    /** Cache-only lookup: never blocks a command thread on a Mojang round trip. */
    private OfflinePlayer lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "buy", "b", "price", "p" -> prefixed(itemIds(), args[1]);
                case "sell", "s" -> {
                    List<String> opts = new ArrayList<>(itemIds());
                    opts.add(0, "hand");
                    opts.add(1, "inv");
                    yield prefixed(opts, args[1]);
                }
                case "pay", "balance", "bal" -> prefixed(names(), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && (sub.equals("buy") || sub.equals("b") || sub.equals("sell") || sub.equals("s"))) {
            return prefixed(List.of("1", "8", "16", "32", "64"), args[2]);
        }
        return List.of();
    }

    private List<String> itemIds() {
        List<String> ids = new ArrayList<>(plugin.prices().size());
        for (PriceEntry e : plugin.prices().all()) {
            ids.add(e.id().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
        }
        return out;
    }

    private static List<String> prefixed(List<String> pool, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : pool) {
            if (s.toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(s);
                if (out.size() >= 60) {
                    break;
                }
            }
        }
        return out;
    }
}
