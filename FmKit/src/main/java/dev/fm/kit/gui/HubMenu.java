package dev.fm.kit.gui;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Hub menu: entry to the private and public bins. */
public final class HubMenu {

    private HubMenu() {
    }

    public static void open(Player p) {
        FmKitPlugin plugin = FmKitPlugin.instance();
        GuiSession s = new GuiSession(plugin, p, GuiSession.View.HUB);
        s.inv = Bukkit.createInventory(s, 9, TextUtil.mini(plugin.settings().msg("hub-title")));
        render(s);
        p.openInventory(s.inv);
        GuiBase.startAutoRefresh(s);
    }

    static void render(GuiSession s) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
        int privateCount = plugin.privateStore().size(s.viewer.getUniqueId());
        int publicCount = plugin.publicStore().size();
        ItemStack[] d = new ItemStack[9];
        d[3] = GuiBase.icon(cfg.icon("hub-private", Material.ENDER_CHEST),
                "<green><bold>私人回收站</bold></green>",
                "<gray>当前 <white>" + privateCount + "</white> 条",
                "<dark_gray>点击进入</dark_gray>");
        d[5] = GuiBase.icon(cfg.icon("hub-public", Material.CHEST),
                "<gold><bold>公共回收站</bold></gold>",
                "<gray>当前 <white>" + publicCount + "</white> 条",
                "<dark_gray>点击进入</dark_gray>");
        GuiBase.apply(s, d);
    }

    static void onClick(GuiSession s, int slot) {
        if (slot == 3) {
            PrivateGui.open(s.viewer, s.viewer.getUniqueId());
        } else if (slot == 5) {
            PublicGui.open(s.viewer);
        }
    }
}
