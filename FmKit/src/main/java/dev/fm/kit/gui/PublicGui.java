package dev.fm.kit.gui;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.bin.BinEntry;
import dev.fm.kit.util.TextUtil;
import dev.fm.kit.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/** Public bin page: first come first served, sort cycle at slot 51. */
public final class PublicGui {

    private PublicGui() {
    }

    public static void open(Player viewer) {
        FmKitPlugin plugin = FmKitPlugin.instance();
        GuiSession s = new GuiSession(plugin, viewer, GuiSession.View.PUBLIC);
        s.inv = Bukkit.createInventory(s, 54, title(plugin));
        render(s);
        viewer.openInventory(s.inv);
        GuiBase.startAutoRefresh(s);
    }

    private static Component title(FmKitPlugin plugin) {
        return TextUtil.mini(TextUtil.apply(plugin.settings().msg("public-title"),
                "n", String.valueOf(plugin.publicStore().size())));
    }

    static void render(GuiSession s) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
        s.inv.clear();
        for (int i = 0; i < 54; i++) {
            s.inv.setItem(i, GuiBase.pane(cfg.frameMaterial()));
        }
        if (s.page == 0) {
            for (int i : GuiBase.darkBarSlots(GuiSession.View.PUBLIC)) {
                s.inv.setItem(i, GuiBase.pane(cfg.darkBarMaterial()));
            }
        }

        List<BinEntry> entries = plugin.publicStore().snapshot();
        long now = System.currentTimeMillis();
        entries.removeIf(e -> e.expireAt() <= now);
        switch (s.sort) {
            case NEWEST -> entries.sort(Comparator.comparingLong(BinEntry::depositAt).reversed());
            case OLDEST -> entries.sort(Comparator.comparingLong(BinEntry::depositAt));
            case EXPIRING -> entries.sort(Comparator.comparingLong(BinEntry::expireAt));
        }
        int pages = GuiBase.pageCount(entries.size());
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        int[] slots = GuiBase.pageSlots(s.page);
        int start = GuiBase.pageStart(s.page);

        s.slotToId.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        for (int i = 0; i < slots.length; i++) {
            int idx = start + i;
            if (idx >= entries.size()) {
                break;
            }
            BinEntry e = entries.get(idx);
            long left = e.expireAt() - now;
            List<String> lore = new ArrayList<>();
            lore.add(TextUtil.apply(cfg.msg("lore-drop-time"), "time", fmt.format(new Date(e.depositAt()))));
            if (e.ownerName() != null) {
                lore.add(TextUtil.apply(cfg.msg("lore-owner"), "name", e.ownerName()));
            } else {
                lore.add(cfg.msg("lore-no-owner"));
            }
            lore.add(TextUtil.apply(cfg.msg("lore-delete"),
                    "color", PrivateGui.expiryColor(left, cfg.publicTtlMs()),
                    "t", TimeUtil.format(Math.max(0, left))));
            lore.add(cfg.msg("lore-hint-public"));
            s.inv.setItem(slots[i], GuiBase.card(e.item(), lore));
            s.slotToId.add(e.id());
        }

        int max = cfg.publicMaxEntries();
        if (s.page == 0) {
            s.inv.setItem(GuiBase.SLOT_BOOK, GuiBase.icon(cfg.icon("banner", Material.WRITABLE_BOOK),
                    "<gold><bold>公共回收站说明</bold></gold>",
                    "<gray>共 <white>" + entries.size() + "</white> 条",
                    max <= 0 ? "<gray>容量上限：<white>无限</white> · 页数无限"
                            : "<gray>容量上限：<white>" + max + " 件</white> · " + GuiBase.pageCount(max) + " 页",
                    "<gray>来源：扫地无主 / 私人箱到期转入",
                    "<dark_gray>先到先得，过期删除</dark_gray>"));
        }

        s.inv.setItem(GuiBase.SLOT_PREV, GuiBase.icon(cfg.icon("prev-page", Material.ARROW), "<white>上一页"));
        s.inv.setItem(GuiBase.SLOT_NEXT, GuiBase.icon(cfg.icon("next-page", Material.ARROW), "<white>下一页"));
        s.inv.setItem(GuiBase.SLOT_PAGE, GuiBase.icon(cfg.icon("page-indicator", Material.PAPER),
                "<white>第 " + (s.page + 1) + "/" + pages + " 页"));
        s.inv.setItem(GuiBase.SLOT_SWITCH, GuiBase.icon(cfg.icon("switch-to-private", Material.ENDER_CHEST),
                "<green><bold>切到私人回收站</bold></green>"));
        s.inv.setItem(GuiBase.SLOT_REFRESH, GuiBase.icon(cfg.icon("refresh", Material.CLOCK), "<white>刷新"));
        s.inv.setItem(GuiBase.SLOT_SORT, GuiBase.icon(cfg.icon("sort", Material.HOPPER),
                "<white>排序：" + GuiBase.sortName(s.sort),
                "<dark_gray>点击切换</dark_gray>"));
        s.inv.setItem(GuiBase.SLOT_TAKE_ALL, GuiBase.icon(cfg.icon("take-all", Material.LIME_SHULKER_BOX),
                "<green><bold>全部取回</bold></green>",
                "<dark_gray>按存入顺序装入背包，装满即停</dark_gray>"));
    }



    static void onClick(GuiSession s, int slot, ClickType click) {
        var plugin = s.plugin;
        Player p = s.viewer;

        if (slot == GuiBase.SLOT_PREV) {
            if (s.page > 0) {
                s.page--;
                render(s);
                GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            }
            return;
        }
        if (slot == GuiBase.SLOT_NEXT) {
            s.page++;
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_REFRESH) {
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_SWITCH) {
            PrivateGui.open(p, p.getUniqueId());
            return;
        }
        if (slot == GuiBase.SLOT_SORT) {
            s.sort = switch (s.sort) {
                case NEWEST -> GuiSession.Sort.OLDEST;
                case OLDEST -> GuiSession.Sort.EXPIRING;
                case EXPIRING -> GuiSession.Sort.NEWEST;
            };
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_TAKE_ALL) {
            takeAll(s);
            return;
        }

        int idx = -1;
        int[] slots = GuiBase.pageSlots(s.page);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx >= s.slotToId.size()) {
            return;
        }
        String id = s.slotToId.get(idx);
        if (click.isLeftClick()) {
            take(s, id);
        }
    }

    private static void take(GuiSession s, String id) {
        var plugin = s.plugin;
        Player p = s.viewer;
        BinEntry e = plugin.publicStore().take(id);
        if (e == null) {
            TextUtil.msg(p, plugin.settings().prefixed("entry-gone"));
            render(s);
            return;
        }
        if (e.expireAt() <= System.currentTimeMillis()) {
            plugin.publicStore().putBack(e);
            TextUtil.msg(p, plugin.settings().prefixed("entry-expired"));
            render(s);
            return;
        }
        if (!GuiBase.canFit(p.getInventory(), e.item())) {
            plugin.publicStore().putBack(e);
            TextUtil.msg(p, plugin.settings().prefixed("bag-full"));
            render(s);
            return;
        }
        p.getInventory().addItem(e.item());
        TextUtil.msg(p, plugin.settings().prefixed("public-taken"));
        GuiBase.sound(p, plugin, Sound.ENTITY_ITEM_PICKUP);
        render(s);
    }

    private static void takeAll(GuiSession s) {
        var plugin = s.plugin;
        Player p = s.viewer;
        List<BinEntry> all = plugin.publicStore().snapshot();
        long now = System.currentTimeMillis();
        all.removeIf(e -> e.expireAt() <= now);
        all.sort(Comparator.comparingLong(BinEntry::depositAt));
        int n = 0;
        for (BinEntry peek : all) {
            if (!GuiBase.canFit(p.getInventory(), peek.item())) {
                break;
            }
            BinEntry taken = plugin.publicStore().take(peek.id());
            if (taken == null) {
                continue;
            }
            if (taken.expireAt() <= System.currentTimeMillis()) {
                plugin.publicStore().putBack(taken);
                continue;
            }
            p.getInventory().addItem(taken.item());
            n++;
        }
        if (n > 0) {
            TextUtil.msg(p, TextUtil.apply(plugin.settings().prefixed("taken-all-public"),
                    "n", String.valueOf(n)));
            GuiBase.sound(p, plugin, Sound.ENTITY_ITEM_PICKUP);
        }
        render(s);
    }
}
