package dev.fm.shop.cmd;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.Settings;
import dev.fm.shop.store.PlayerData;
import dev.fm.shop.store.PriceDoctor;
import dev.fm.shop.store.PriceEntry;
import dev.fm.shop.util.ItemNames;
import dev.fm.shop.util.Money;
import dev.fm.shop.util.TextUtil;
import dev.fm.shop.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /fmshopadmin} - operator surface.
 *
 * <p>Money commands go through {@link dev.fm.shop.economy.FmEconomy} rather than
 * touching {@code PlayerData} directly, so an offline target is loaded, edited
 * and flushed by the same path an online one takes. Every balance change is
 * written to the audit log with the actor's name: an economy without an audit
 * trail cannot be argued about after the fact.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "give", "take", "set", "price", "market", "resetlimit",
            "reload", "status", "audit", "doctor", "tax", "help");

    private final FmShopPlugin plugin;

    public AdminCommand(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "take", "set" -> money(sender, args);
            case "price" -> price(sender, args);
            case "market" -> market(sender, args);
            case "resetlimit" -> resetLimit(sender, args);
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "audit" -> audit(sender, args);
            case "doctor" -> doctor(sender);
            case "tax" -> tax(sender, args);
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        TextUtil.send(sender, "<gold>商店管理指令");
        TextUtil.send(sender, "<yellow>/" + label + " give|take|set <玩家> <金额>");
        TextUtil.send(sender, "<yellow>/" + label + " price <物品> <买入> <卖出> <gray>写入 prices.yml 并重载");
        TextUtil.send(sender, "<yellow>/" + label + " market <物品> [reset] <gray>查看/重置行情");
        TextUtil.send(sender, "<yellow>/" + label + " resetlimit <玩家|*> <gray>清除今日限额");
        TextUtil.send(sender, "<yellow>/" + label + " tax [take <玩家> <金额>] <gray>公共税池");
        TextUtil.send(sender, "<yellow>/" + label + " audit [玩家] [条数] <gray>查看交易记录");
        TextUtil.send(sender, "<yellow>/" + label + " doctor <gray>价格表体检");
        TextUtil.send(sender, "<yellow>/" + label + " status <gray>运行状态");
        TextUtil.send(sender, "<yellow>/" + label + " reload <gray>重载配置");
    }

    // ----------------------------------------------------------- money edits

    private void money(CommandSender sender, String[] args) {
        if (args.length < 3) {
            TextUtil.send(sender, "<red>用法：/fsa " + args[0] + " <玩家> <金额>");
            return;
        }
        OfflinePlayer target = lookup(args[1]);
        if (target == null) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("player-not-found"),
                    "player", args[1]));
            return;
        }
        long cents = Money.parse(args[2]);
        if (cents < 0) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("bad-amount"),
                    "input", args[2]));
            return;
        }
        UUID id = target.getUniqueId();
        String kind = args[0].toLowerCase(Locale.ROOT);
        boolean ok = switch (kind) {
            case "give" -> plugin.economy().deposit(id, cents);
            case "take" -> plugin.economy().withdraw(id, cents);
            default -> {
                plugin.economy().set(id, cents);
                yield true;
            }
        };
        if (!ok) {
            TextUtil.send(sender, prefix() + plugin.settings().msg("pay-failed"));
            return;
        }
        long after = plugin.economy().balance(id);
        String key = switch (kind) {
            case "give" -> "admin-give";
            case "take" -> "admin-take";
            default -> "admin-set";
        };
        TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg(key),
                "player", String.valueOf(target.getName()),
                "amount", money(cents)));
        plugin.audit().logAdmin(sender.getName(), String.valueOf(target.getName()),
                kind.toUpperCase(Locale.ROOT), cents, after);
    }

    // ---------------------------------------------------------- price edits

    /**
     * Rewrites one row in prices.yml, then reloads the table.
     *
     * <p>Editing the file rather than a memory-only override is deliberate: an
     * admin price change survives a restart, and the operator can still see and
     * revert it in the file they already know.
     */
    private void price(CommandSender sender, String[] args) {
        if (args.length < 4) {
            TextUtil.send(sender, "<red>用法：/fsa price <物品> <买入> <卖出>（0 = 不交易）");
            return;
        }
        Material mat = plugin.prices().match(args[1]);
        if (mat == null) {
            mat = Material.matchMaterial(args[1]);
        }
        if (mat == null || !mat.isItem()) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("unknown-item"),
                    "input", args[1]));
            return;
        }
        long buy = Money.parse(args[2]);
        long sell = Money.parse(args[3]);
        if (buy < 0 || sell < 0) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("bad-amount"),
                    "input", args[2] + "/" + args[3]));
            return;
        }
        if (buy > 0 && sell >= buy) {
            TextUtil.send(sender, "<red>拒绝：卖出价必须低于买入价，否则可无限刷钱");
            return;
        }
        File file = plugin.pricesFile();
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            TextUtil.send(sender, "<red>prices.yml 读取失败：<white>" + ex.getMessage());
            return;
        }
        String path = "items." + mat.name();
        PriceEntry old = plugin.prices().get(mat);
        y.set(path + ".buy", buy / (double) Money.SCALE);
        y.set(path + ".sell", sell / (double) Money.SCALE);
        if (!y.isSet(path + ".category")) {
            y.set(path + ".category", old != null ? old.category() : "misc");
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            TextUtil.send(sender, "<red>prices.yml 写入失败：<white>" + ex.getMessage());
            return;
        }
        plugin.reload();
        TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("admin-price"),
                "item", ItemNames.mini(mat), "buy", money(buy), "sell", money(sell)));
        plugin.audit().logAdmin(sender.getName(), mat.name(), "PRICE", buy, sell);
    }

    // -------------------------------------------------------------- market

    private void market(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TextUtil.send(sender, "<red>用法：/fsa market <物品> [reset]");
            return;
        }
        Material mat = plugin.prices().match(args[1]);
        PriceEntry e = mat == null ? null : plugin.prices().get(mat);
        if (e == null) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("unknown-item"),
                    "input", args[1]));
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            plugin.market().reset(e.material());
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("admin-market-reset"),
                    "item", ItemNames.mini(e.material())));
            plugin.audit().logAdmin(sender.getName(), e.material().name(), "MARKET-RESET", 0, 0);
            return;
        }
        long now = System.currentTimeMillis();
        TextUtil.send(sender, prefix() + ItemNames.mini(e.material())
                + " <gray>行情 <white>" + Math.round(plugin.market().multiplierBp(e, now) / 100f) + "%");
        TextUtil.send(sender, "<gray>现价 买入 <white>" + money(plugin.market().buyUnit(e, now))
                + "</white> 卖出 <white>" + money(plugin.market().sellUnit(e, now)));
        TextUtil.send(sender, "<gray>基准 买入 <white>" + money(e.buy())
                + "</white> 卖出 <white>" + money(e.sell())
                + "</white>，累计买 <white>" + plugin.market().bought(e.material())
                + "</white> 卖 <white>" + plugin.market().sold(e.material()));
    }

    // ---------------------------------------------------------- quota reset

    private void resetLimit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TextUtil.send(sender, "<red>用法：/fsa resetlimit <玩家|*>");
            return;
        }
        if (args[1].equals("*")) {
            int n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = plugin.data().loadSync(p.getUniqueId());
                d.resetQuotas();
                plugin.data().save(p.getUniqueId(), d);
                n++;
            }
            TextUtil.send(sender, prefix() + "<green>已重置 <white>" + n + "</white> 名在线玩家的今日限额");
            plugin.audit().logAdmin(sender.getName(), "*", "RESETLIMIT", n, 0);
            return;
        }
        OfflinePlayer target = lookup(args[1]);
        if (target == null) {
            TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("player-not-found"),
                    "player", args[1]));
            return;
        }
        PlayerData d = plugin.data().loadSync(target.getUniqueId());
        d.resetQuotas();
        plugin.data().save(target.getUniqueId(), d);
        TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("admin-reset-limit"),
                "player", String.valueOf(target.getName())));
        plugin.audit().logAdmin(sender.getName(), String.valueOf(target.getName()), "RESETLIMIT", 0,
                d.balance());
    }

    // ---------------------------------------------------------------- misc

    private void reload(CommandSender sender) {
        plugin.reload();
        TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("reloaded"),
                "items", String.valueOf(plugin.prices().size()),
                "cats", String.valueOf(plugin.prices().categories().size())));
    }

    private void status(CommandSender sender) {
        var s = plugin.settings();
        TextUtil.send(sender, "<gold>FmShop 状态");
        TextUtil.send(sender, "<gray>商品 <white>" + plugin.prices().size()
                + "</white> 件，分类 <white>" + plugin.prices().categories().size()
                + "</white>，未知 ID <white>" + plugin.prices().unknown().size());
        TextUtil.send(sender, "<gray>账户 已加载 <white>" + plugin.data().loadedCount()
                + "</white>，存档 <white>" + plugin.data().fileCount());
        TextUtil.send(sender, "<gray>手续费 卖 <white>" + s.sellFeeBp() / 100.0
                + "%</white> 买 <white>" + s.buyFeeBp() / 100.0
                + "%</white> 转账 <white>" + s.payFeeBp() / 100.0
                + "%</white> → <white>" + (s.feeSink() == Settings.FeeSink.TAX_POOL ? "税池" : "销毁"));
        TextUtil.send(sender, "<gray>税池 <white>" + money(plugin.tax().balance())
                + "</white>（累计 <white>" + money(plugin.tax().lifetime()) + "</white>）");
        TextUtil.send(sender, "<gray>行情已波动 <white>" + plugin.market().movedCount()
                + "</white> 种，界面打开 <white>" + plugin.gui().openCount()
                + "</white>，日志文件 <white>" + plugin.audit().fileCount());
        TextUtil.send(sender, "<gray>限额重置 <white>" + s.resetHour() + ":00</white>，距下次 <white>"
                + TimeUtil.format(s.untilReset())
                + "</white>，严格模式 <white>" + (s.doctorStrict() ? "开" : "关"));
    }

    private void audit(CommandSender sender, String[] args) {
        if (!plugin.settings().auditEnabled()) {
            TextUtil.send(sender, prefix() + "<gray>审计日志已关闭（audit.enabled: false）");
            return;
        }
        String who = args.length >= 2 ? args[1] : null;
        int limit = 10;
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException ignored) {
                limit = 10;
            }
        }
        plugin.audit().tail(who, limit, lines -> {
            if (lines.isEmpty()) {
                TextUtil.send(sender, prefix() + "<gray>没有匹配的交易记录");
                return;
            }
            TextUtil.send(sender, "<gold>最近 " + lines.size() + " 条交易"
                    + (who == null ? "" : "（" + who + "）"));
            for (String line : lines) {
                TextUtil.send(sender, "<gray>" + line);
            }
        });
    }

    private void doctor(CommandSender sender) {
        List<PriceDoctor.Finding> findings = PriceDoctor.run(plugin.prices());
        if (findings.isEmpty()) {
            TextUtil.send(sender, prefix() + "<green>价格表体检通过：未发现套利路径");
            return;
        }
        int errors = 0;
        int warns = 0;
        for (PriceDoctor.Finding f : findings) {
            if (f.severity() == PriceDoctor.Severity.ERROR) {
                errors++;
            } else if (f.severity() == PriceDoctor.Severity.WARN) {
                warns++;
            }
        }
        TextUtil.send(sender, "<gold>价格表体检：<red>" + errors + " 错误</red> <yellow>"
                + warns + " 警告");
        int shown = 0;
        for (PriceDoctor.Finding f : findings) {
            if (shown++ >= 20) {
                for (PriceDoctor.Finding rest : findings.subList(20, findings.size())) {
                    plugin.getSLF4JLogger().info("体检：{}", rest.text());
                }
                TextUtil.send(sender, "<gray>… 其余 " + (findings.size() - 20) + " 条见控制台");
                break;
            }
            String color = switch (f.severity()) {
                case ERROR -> "<red>";
                case WARN -> "<yellow>";
                case INFO -> "<gray>";
            };
            TextUtil.send(sender, color + f.text());
        }
        if (errors > 0 && !plugin.settings().doctorStrict()) {
            TextUtil.send(sender, "<gray>提示：doctor.strict: true 可在启动时自动下架这些商品");
        }
    }

    private void tax(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("take")) {
            if (args.length < 4) {
                TextUtil.send(sender, "<red>用法：/fsa tax take <玩家> <金额>");
                return;
            }
            OfflinePlayer target = lookup(args[2]);
            if (target == null) {
                TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("player-not-found"),
                        "player", args[2]));
                return;
            }
            long cents = Money.parse(args[3]);
            if (cents <= 0) {
                TextUtil.send(sender, prefix() + TextUtil.apply(plugin.settings().msg("bad-amount"),
                        "input", args[3]));
                return;
            }
            if (!plugin.tax().take(cents)) {
                TextUtil.send(sender, prefix() + "<red>税池余额不足：<white>"
                        + money(plugin.tax().balance()));
                return;
            }
            if (!plugin.economy().deposit(target.getUniqueId(), cents)) {
                plugin.tax().add(cents);
                TextUtil.send(sender, prefix() + plugin.settings().msg("pay-failed"));
                return;
            }
            TextUtil.send(sender, prefix() + "<green>已从税池发放 <white>" + money(cents)
                    + "</white> 给 <white>" + target.getName());
            plugin.audit().logAdmin(sender.getName(), String.valueOf(target.getName()), "TAX-TAKE",
                    cents, plugin.economy().balance(target.getUniqueId()));
            return;
        }
        TextUtil.send(sender, prefix() + "<gray>税池余额 <white>" + money(plugin.tax().balance())
                + "</white>，累计收取 <white>" + money(plugin.tax().lifetime()));
    }

    // ------------------------------------------------------------- helpers

    private String prefix() {
        return plugin.settings().msg("prefix");
    }

    private String money(long cents) {
        return Money.format(cents, plugin.settings().currency());
    }

    private OfflinePlayer lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "give", "take", "set", "audit" -> prefixed(names(), args[1]);
                case "resetlimit" -> {
                    List<String> opts = new ArrayList<>(names());
                    opts.add(0, "*");
                    yield prefixed(opts, args[1]);
                }
                case "price", "market" -> prefixed(itemIds(), args[1]);
                case "tax" -> prefixed(List.of("take"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "market" -> prefixed(List.of("reset"), args[2]);
                case "tax" -> prefixed(names(), args[2]);
                case "give", "take", "set" -> prefixed(List.of("100", "1000", "10000"), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> itemIds() {
        List<String> ids = new ArrayList<>(plugin.prices().size());
        for (PriceEntry e : plugin.prices().all()) {
            ids.add(e.material().name().toLowerCase(Locale.ROOT));
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
