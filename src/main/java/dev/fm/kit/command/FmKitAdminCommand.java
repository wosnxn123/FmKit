package dev.fm.kit.command;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.Settings;
import dev.fm.kit.bin.PrivateBin;
import dev.fm.kit.bin.NotifyMode;
import dev.fm.kit.gui.PrivateGui;
import dev.fm.kit.util.TextUtil;
import dev.fm.kit.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * /fmkitadmin help | clearpublic | clear &lt;player&gt; | bin &lt;player&gt; | reload
 *             | sweep &lt;on|off|now&gt; | interval &lt;seconds&gt;
 *             | whitelist &lt;valuable|ignore&gt; [add|remove|clear|on|off]
 *             | toggle &lt;player&gt; &lt;on|off&gt; | notify &lt;player&gt; &lt;off|valuable|all&gt;
 *             | destroy &lt;player&gt; &lt;on|off&gt; | status
 */
public final class FmKitAdminCommand implements TabExecutor {

    private final FmKitPlugin plugin;
    private final Map<String, Long> clearPublicPending = new HashMap<>();

    public FmKitAdminCommand(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var cfg = plugin.settings();
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reload();
                TextUtil.send(sender, cfg.prefixed("reload-done"));
            }
            case "sweep" -> sweep(sender, args);
            case "interval" -> interval(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "status" -> status(sender);
            case "help" -> help(sender);
            case "clearpublic" -> clearPublic(sender);
            case "clear" -> clearPlayer(sender, args);
            case "bin" -> binPlayer(sender, args);
            case "toggle" -> togglePlayer(sender, args);
            case "notify" -> notifyPlayer(sender, args);
            case "destroy" -> destroyPlayer(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        String pre = plugin.settings().msg("prefix");
        TextUtil.send(sender, pre + "<aqua><bold>回收站管理指令帮助</bold></aqua>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin status</white> <gray>— 查看运行状态</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin sweep <on|off|now></white> <gray>— 扫地开关 / 立即扫一次</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin interval <秒></white> <gray>— 设置清理间隔</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin whitelist <valuable|ignore> [add|remove|clear|on|off]</white> <gray>— 清单管理（无参查看）</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin notify <玩家> <off|valuable|all></white> <gray>— 改玩家到期提醒档</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin destroy <玩家> <on|off></white> <gray>— 改玩家到期去向</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin bin <玩家></white> <gray>— 查看玩家私人箱</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin toggle <玩家> <on|off></white> <gray>— 改玩家回收开关</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin clear <玩家></white> <gray>— 清空玩家私人箱</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin clearpublic</white> <gray>— 清空公共箱（10秒内两次确认）</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin reload</white> <gray>— 重载配置</gray>");
        TextUtil.send(sender, pre + "<white>/fmkitadmin help</white> <gray>— 显示本帮助</gray>");
    }

    private void sweep(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("now")) {
            plugin.sweep().triggerClean();
            TextUtil.send(sender, plugin.settings().prefixed("sweep-now"));
            return;
        }
        if (args.length < 2) {
            TextUtil.send(sender, plugin.settings().msg("prefix") + "<gray>用法：/fmkitadmin sweep <on|off|now>");
            return;
        }
        Boolean on = FmKitCommand.parseBool(args[1]);
        if (on == null) {
            TextUtil.send(sender, plugin.settings().msg("prefix") + "<gray>用法：/fmkitadmin sweep <on|off|now>");
            return;
        }
        plugin.sweep().setRuntimeEnabled(on);
        TextUtil.send(sender, plugin.settings().prefixed(on ? "sweep-on" : "sweep-off"));
    }

    private void interval(CommandSender sender, String[] args) {
        if (args.length < 2) {
            TextUtil.send(sender, plugin.settings().msg("prefix") + "<gray>用法：/fmkitadmin interval <秒>");
            return;
        }
        int sec;
        try {
            sec = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            TextUtil.send(sender, plugin.settings().msg("prefix") + "<gray>用法：/fmkitadmin interval <秒>");
            return;
        }
        sec = Math.max(5, sec);
        plugin.settings().setCleanInterval(sec);
        plugin.sweep().applyConfig();
        TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("interval-set"), "s", String.valueOf(sec)));
    }

    private void whitelist(CommandSender sender, String[] args) {
        var cfg = plugin.settings();
        if (args.length == 1) {
            showList(sender, true);
            showList(sender, false);
            return;
        }
        String which = args[1].toLowerCase();
        if (!which.equals("valuable") && !which.equals("ignore")) {
            TextUtil.send(sender, cfg.msg("prefix") + "<gray>用法：/fmkitadmin whitelist <valuable|ignore> [add|remove|clear|on|off]");
            return;
        }
        boolean valuable = which.equals("valuable");
        String listName = valuable ? "贵重清单" : "扫地豁免清单";
        if (args.length == 2) {
            showList(sender, valuable);
            return;
        }
        switch (args[2].toLowerCase()) {
            case "add", "remove" -> {
                if (args.length < 4) {
                    TextUtil.send(sender, cfg.msg("prefix") + "<gray>用法：/fmkitadmin whitelist " + which + " " + args[2] + " <物品>");
                    return;
                }
                Material m = Material.matchMaterial(args[3]);
                if (m == null) {
                    TextUtil.send(sender, TextUtil.apply(cfg.prefixed("list-bad-item"), "item", args[3]));
                    return;
                }
                boolean add = args[2].equalsIgnoreCase("add");
                boolean ok = add
                        ? (valuable ? cfg.valuableAdd(m.name()) : cfg.ignoreAdd(m.name()))
                        : (valuable ? cfg.valuableRemove(m.name()) : cfg.ignoreRemove(m.name()));
                String key = add ? (ok ? "list-add" : "list-add-dup") : (ok ? "list-remove" : "list-remove-missing");
                TextUtil.send(sender, TextUtil.apply(cfg.prefixed(key), "list", listName, "item", m.name()));
            }
            case "clear" -> {
                if (valuable) {
                    cfg.valuableClear();
                } else {
                    cfg.ignoreClear();
                }
                TextUtil.send(sender, TextUtil.apply(cfg.prefixed("list-clear"), "list", listName));
            }
            case "on", "off" -> {
                boolean on = args[2].equalsIgnoreCase("on");
                if (valuable) {
                    cfg.setValuableEnabled(on);
                } else {
                    cfg.setIgnoreEnabled(on);
                }
                TextUtil.send(sender, TextUtil.apply(cfg.prefixed(on ? "list-on" : "list-off"), "list", listName));
            }
            default -> TextUtil.send(sender, cfg.msg("prefix") + "<gray>用法：/fmkitadmin whitelist <valuable|ignore> [add|remove|clear|on|off]");
        }
    }

    private void showList(CommandSender sender, boolean valuable) {
        var cfg = plugin.settings();
        List<String> names = valuable ? cfg.valuableNames() : cfg.ignoreNames();
        boolean on = valuable ? cfg.valuableEnabled() : cfg.ignoreEnabled();
        String listName = valuable ? "贵重清单" : "扫地豁免清单";
        TextUtil.send(sender, TextUtil.apply(cfg.prefixed("list-header"), "list", listName,
                "state", on ? "<green>启用</green>" : "<red>关闭</red>", "n", String.valueOf(names.size())));
        TextUtil.send(sender, cfg.msg("prefix") + (names.isEmpty() ? cfg.msg("list-empty")
                : "<white>" + String.join(", ", names) + "</white>"));
    }

    private void status(CommandSender sender) {
        var s = plugin.settings();
        int players = plugin.privateStore().bins().size();
        int privateEntries = plugin.privateStore().totalEntries();
        int onCount = 0;
        for (PrivateBin b : plugin.privateStore().bins().values()) {
            if (b.collectEnabled()) {
                onCount++;
            }
        }
        int publicSize = plugin.publicStore().size();
        long oldest = plugin.publicStore().oldestDeposit();
        String oldestText = oldest < 0 ? "-" : TimeUtil.format(System.currentTimeMillis() - oldest) + "前";
        TextUtil.send(sender, "<aqua><bold>FmKit 状态</bold></aqua>");
        TextUtil.send(sender, "<gray>私人箱：</gray><white>" + players + "</white> 位玩家 / <white>" + privateEntries + "</white> 条");
        TextUtil.send(sender, "<gray>公共箱：</gray><white>" + publicSize + "</white> 条"
                + (s.publicMaxEntries() > 0 ? "（上限 " + s.publicMaxEntries() + "）" : "") + " · 最旧：" + oldestText);
        TextUtil.send(sender, "<gray>回收开关：</gray><green>开 " + onCount + "</green> / <red>关 " + (players - onCount) + "</red>");
        TextUtil.send(sender, "<gray>贵重清单：</gray>" + (s.valuableEnabled() ? "<green>启用</green>" : "<red>关闭</red>")
                + " · <white>" + s.valuableNames().size() + "</white> 种（到期提醒过滤）");
        TextUtil.send(sender, "<gray>扫地豁免：</gray>" + (s.ignoreEnabled() ? "<green>启用</green>" : "<red>关闭</red>")
                + " · <white>" + s.ignoreNames().size() + "</white> 种");
        TextUtil.send(sender, "<gray>扫地：</gray>" + (plugin.sweep().isRunning() ? "<green>运行中</green>" : "<red>暂停</red>")
                + (plugin.sweep().isWanted() ? "" : "（手动关闭）"));
    }

    private void clearPublic(CommandSender sender) {
        var cfg = plugin.settings();
        long now = System.currentTimeMillis();
        String key = sender instanceof Player p ? p.getUniqueId().toString() : "console:" + sender.getName();
        Long deadline = clearPublicPending.remove(key);
        if (deadline != null && now < deadline) {
            int n = plugin.publicStore().clear();
            plugin.getLogger().info("[管理员操作] " + sender.getName() + " 清空了公共回收站，共 " + n + " 条");
            TextUtil.send(sender, TextUtil.apply(cfg.prefixed("clearpublic-done"), "n", String.valueOf(n)));
            return;
        }
        int n = plugin.publicStore().size();
        clearPublicPending.put(key, now + cfg.clearpublicConfirmSeconds() * 1000L);
        TextUtil.send(sender, TextUtil.apply(cfg.prefixed("clearpublic-confirm"),
                "n", String.valueOf(n), "s", String.valueOf(cfg.clearpublicConfirmSeconds())));
    }

    private void clearPlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            help(sender);
            return;
        }
        String name = args[1];
        resolveUuid(name, uuid -> {
            int n = plugin.privateStore().clear(uuid);
            plugin.getLogger().info("[管理员操作] " + sender.getName() + " 清空了玩家 " + name + " 的私人箱，共 " + n + " 条");
            TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("clear-player-done"),
                    "player", name, "n", String.valueOf(n)));
        }, () -> TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("player-not-found"), "player", name)));
    }

    private void binPlayer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("仅玩家可用");
            return;
        }
        if (args.length < 2) {
            help(sender);
            return;
        }
        String name = args[1];
        resolveUuid(name, uuid -> PrivateGui.open(p, uuid),
                () -> TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("player-not-found"), "player", name)));
    }

    private void togglePlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        String name = args[1];
        Boolean v = FmKitCommand.parseBool(args[2]);
        if (v == null) {
            help(sender);
            return;
        }
        resolveUuid(name, uuid -> {
            plugin.privateStore().setCollectEnabled(uuid, v);
            TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed(v ? "toggle-on" : "toggle-off"),
                    "player", name));
        }, () -> TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("player-not-found"), "player", name)));
    }

    private void notifyPlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        String name = args[1];
        NotifyMode mode = switch (args[2].toLowerCase()) {
            case "off" -> NotifyMode.OFF;
            case "valuable" -> NotifyMode.VALUABLE;
            case "all" -> NotifyMode.ALL;
            default -> null;
        };
        if (mode == null) {
            TextUtil.send(sender, plugin.settings().msg("prefix") + "<gray>用法：/fmkitadmin notify <玩家> <off|valuable|all>");
            return;
        }
        String modeText = switch (mode) {
            case OFF -> "关";
            case VALUABLE -> "只提醒贵重";
            case ALL -> "开";
        };
        resolveUuid(name, uuid -> {
            plugin.privateStore().setNotifyMode(uuid, mode);
            TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("notify-set"),
                    "player", name, "mode", modeText));
        }, () -> TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("player-not-found"), "player", name)));
    }

    private void destroyPlayer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        String name = args[1];
        Boolean v = FmKitCommand.parseBool(args[2]);
        if (v == null) {
            help(sender);
            return;
        }
        resolveUuid(name, uuid -> {
            plugin.privateStore().setExpiryDestroy(uuid, v);
            TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("destroy-set"),
                    "player", name, "mode", v ? "自动销毁" : "转公共"));
        }, () -> TextUtil.send(sender, TextUtil.apply(plugin.settings().prefixed("player-not-found"), "player", name)));
    }

    /** Resolve by exact online name, else async offline lookup (Folia-safe). */
    private void resolveUuid(String name, Consumer<UUID> ok, Runnable fail) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            ok.accept(online.getUniqueId());
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            UUID u = op.hasPlayedBefore() || op.isOnline() ? op.getUniqueId() : null;
            Bukkit.getGlobalRegionScheduler().run(plugin, tt -> {
                if (u == null) {
                    fail.run();
                } else {
                    ok.accept(u);
                }
            });
        });
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return FmKitCommand.filter(List.of("help", "clearpublic", "clear", "bin", "reload", "sweep",
                    "interval", "whitelist", "toggle", "notify", "destroy", "status"), args[0]);
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && (sub.equals("clear") || sub.equals("bin") || sub.equals("toggle")
                || sub.equals("notify") || sub.equals("destroy"))) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            return FmKitCommand.filter(names, args[1]);
        }
        if (args.length == 2 && sub.equals("sweep")) {
            return FmKitCommand.filter(List.of("on", "off", "now"), args[1]);
        }
        if (args.length == 2 && sub.equals("whitelist")) {
            return FmKitCommand.filter(List.of("valuable", "ignore"), args[1]);
        }
        if (args.length == 3 && sub.equals("whitelist")) {
            return FmKitCommand.filter(List.of("add", "remove", "clear", "on", "off"), args[2]);
        }
        if (args.length == 4 && sub.equals("whitelist") && args[2].equalsIgnoreCase("add")) {
            String p = args[3].toUpperCase();
            return Arrays.stream(Material.values())
                    .filter(m -> m.name().startsWith(p))
                    .limit(50)
                    .map(Material::name)
                    .toList();
        }
        if (args.length == 4 && sub.equals("whitelist") && args[2].equalsIgnoreCase("remove")) {
            List<String> names = args[1].equalsIgnoreCase("ignore")
                    ? plugin.settings().ignoreNames() : plugin.settings().valuableNames();
            return FmKitCommand.filter(names, args[3]);
        }
        if (args.length == 3 && (sub.equals("toggle") || sub.equals("destroy"))) {
            return FmKitCommand.filter(List.of("on", "off"), args[2]);
        }
        if (args.length == 3 && sub.equals("notify")) {
            return FmKitCommand.filter(List.of("off", "valuable", "all"), args[2]);
        }
        return List.of();
    }
}
