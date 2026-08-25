package dev.fm.kit.command;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.gui.HubMenu;
import dev.fm.kit.gui.PrivateGui;
import dev.fm.kit.gui.PublicGui;
import dev.fm.kit.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /fmkit [private|public|toggle [on|off]|help] */
public final class FmKitCommand implements TabExecutor {

    private final FmKitPlugin plugin;

    public FmKitCommand(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("仅玩家可用");
            return true;
        }
        if (args.length == 0) {
            HubMenu.open(p);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "private" -> PrivateGui.open(p, p.getUniqueId());
            case "public" -> PublicGui.open(p);
            case "toggle" -> toggle(p, args);
            default -> help(p);
        }
        return true;
    }

    private void toggle(Player p, String[] args) {
        var cfg = plugin.settings();
        if (args.length >= 2) {
            Boolean v = parseBool(args[1]);
            if (v == null) {
                TextUtil.msg(p, cfg.msg("prefix") + "<gray>用法：/fmkit toggle [on|off]");
                return;
            }
            plugin.privateStore().setCollectEnabled(p.getUniqueId(), v);
            TextUtil.msg(p, cfg.prefixed(v ? "toggle-on" : "toggle-off"));
        } else {
            boolean cur = plugin.privateStore().isCollectEnabled(p.getUniqueId());
            TextUtil.msg(p, TextUtil.apply(cfg.prefixed("toggle-current"),
                    "state", cur ? "<green>开</green>" : "<red>关</red>"));
        }
    }

    private void help(Player p) {
        String pre = plugin.settings().msg("prefix");
        TextUtil.msg(p, pre + "<aqua><bold>回收站指令帮助</bold></aqua>");
        TextUtil.msg(p, pre + "<white>/fmkit</white> <gray>— 打开回收站大厅</gray>");
        TextUtil.msg(p, pre + "<white>/fmkit private</white> <gray>— 打开私人回收箱</gray>");
        TextUtil.msg(p, pre + "<white>/fmkit public</white> <gray>— 打开公共回收箱</gray>");
        TextUtil.msg(p, pre + "<white>/fmkit toggle [on|off]</white> <gray>— 物品回收开关</gray>");
        TextUtil.msg(p, pre + "<white>/fmkit help</white> <gray>— 显示本帮助</gray>");
    }

    static Boolean parseBool(String s) {
        return switch (s.toLowerCase()) {
            case "on", "true", "开" -> Boolean.TRUE;
            case "off", "false", "关" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("private", "public", "toggle", "help"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("toggle")) {
            return filter(List.of("on", "off"), args[1]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
