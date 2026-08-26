package dev.fm.kit.gui;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.Settings;
import dev.fm.kit.bin.BinEntry;
import dev.fm.kit.bin.NotifyMode;
import dev.fm.kit.util.ItemNames;
import dev.fm.kit.util.TextUtil;
import dev.fm.kit.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private bin page. Left-click take, right-click move-to-public now,
 * shift+right destroy (two-step confirm). Top row page 1: slot 1 recycle switch,
 * 2 expiry reminder, 4 book, 6 expiry destination, 7 expiry preview; slot 52 take-all (all pages).
 */
public final class PrivateGui {

    private PrivateGui() {
    }

    private record Pending(String id, long deadline) { }

    /** Player-keyed so a GUI reopen inside the confirm window keeps the state. */
    private static final Map<UUID, Pending> PENDING_DESTROY = new ConcurrentHashMap<>();

    public static void open(Player viewer, UUID target) {
        FmKitPlugin plugin = FmKitPlugin.instance();
        GuiSession s = new GuiSession(plugin, viewer, GuiSession.View.PRIVATE);
        s.target = target;
        s.sort = GuiSession.Sort.EXPIRING;
        s.inv = Bukkit.createInventory(s, 54, title(plugin, viewer, target));
        render(s);
        viewer.openInventory(s.inv);
        GuiBase.startAutoRefresh(s);
    }

    private static Component title(FmKitPlugin plugin, Player viewer, UUID target) {
        int n = plugin.privateStore().size(target);
        if (viewer.getUniqueId().equals(target)) {
            return TextUtil.mini(TextUtil.apply(plugin.settings().msg("private-title"),
                    "n", String.valueOf(n)));
        }
        String name = plugin.privateStore().knownName(target);
        if (name == null) {
            name = target.toString().substring(0, 8);
        }
        return TextUtil.mini(TextUtil.apply(plugin.settings().msg("private-title-viewing"),
                "player", name, "n", String.valueOf(n)));
    }

    static void render(GuiSession s) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
        ItemStack[] d = new ItemStack[54];
        for (int i = 0; i < 54; i++) {
            d[i] = GuiBase.pane(cfg.frameMaterial());
        }
        if (s.page == 0) {
            for (int i : GuiBase.darkBarSlots(GuiSession.View.PRIVATE)) {
                d[i] = GuiBase.pane(cfg.darkBarMaterial());
            }
        }

        List<BinEntry> entries = plugin.privateStore().snapshot(s.target);
        long now = System.currentTimeMillis();
        entries.removeIf(e -> e.expireAt() <= now);
        // Equal timestamps are common within one sweep round; the id tiebreak keeps
        // positions stable even when takeEntry/putBack moves an entry in the list.
        switch (s.sort) {
            case NEWEST -> entries.sort(Comparator.comparingLong(BinEntry::depositAt).reversed()
                    .thenComparing(BinEntry::id));
            case OLDEST -> entries.sort(Comparator.comparingLong(BinEntry::depositAt)
                    .thenComparing(BinEntry::id));
            case EXPIRING -> entries.sort(Comparator.comparingLong(BinEntry::expireAt)
                    .thenComparing(BinEntry::id));
        }
        int pages = GuiBase.pageCount(entries.size());
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        int[] slots = GuiBase.pageSlots(s.page);
        int start = GuiBase.pageStart(s.page);

        s.slotToId.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        boolean expireDestroy = plugin.privateStore().isExpiryDestroy(s.target);
        for (int i = 0; i < slots.length; i++) {
            int idx = start + i;
            if (idx >= entries.size()) {
                break;
            }
            BinEntry e = entries.get(idx);
            long left = e.expireAt() - now;
            List<String> lore = new ArrayList<>();
            lore.add(TextUtil.apply(cfg.msg("lore-drop-time"), "time", fmt.format(new Date(e.depositAt()))));
            lore.add(TextUtil.apply(cfg.msg(expireDestroy ? "lore-delete" : "lore-expiry"),
                    "color", expiryColor(left, cfg.privateTtlMs()),
                    "t", TimeUtil.format(Math.max(0, left))));
            lore.add(cfg.msg("lore-hint-private"));
            d[slots[i]] = GuiBase.card(e.item(), lore);
            s.slotToId.add(e.id());
        }

        int max = cfg.privateMaxEntries();
        if (s.page == 0) {
            d[GuiBase.SLOT_BOOK] = GuiBase.icon(cfg.icon("banner", Material.WRITABLE_BOOK),
                    "<green><bold>回收箱说明</bold></green>",
                    "<gray>共 <white>" + entries.size() + "</white> 条",
                    max <= 0 ? "<gray>容量上限：<white>无限</white> · 页数无限"
                            : "<gray>容量上限：<white>" + max + " 件</white> · " + GuiBase.pageCount(max) + " 页",
                    "<dark_gray>左键取回 · 右键转公共 · Shift右键销毁</dark_gray>");

            boolean on = plugin.privateStore().isCollectEnabled(s.target);
            d[GuiBase.SLOT_TOGGLE] = GuiBase.icon(
                    cfg.icon(on ? "toggle-on" : "toggle-off", on ? Material.LIME_CONCRETE : Material.RED_CONCRETE),
                    on ? "<green><bold>回收：开</bold></green>" : "<red><bold>回收：关</bold></red>",
                    "<gray>丢弃/死亡掉落 " + (on ? "进入你的私人回收站" : "原版落地，扫到进公共箱"),
                    "<dark_gray>点击切换</dark_gray>");

            NotifyMode notify = plugin.privateStore().notifyMode(s.target);
            boolean valuedOff = cfg.valuableItems().isEmpty();
            d[GuiBase.SLOT_NOTIFY] = GuiBase.icon(
                    cfg.icon(switch (notify) {
                        case OFF -> "toggle-off";
                        case VALUABLE -> "notify-valuable";
                        case ALL -> "toggle-on";
                    }, switch (notify) {
                        case OFF -> Material.RED_CONCRETE;
                        case VALUABLE -> Material.YELLOW_CONCRETE;
                        case ALL -> Material.LIME_CONCRETE;
                    }),
                    switch (notify) {
                        case OFF -> "<red><bold>到期提醒：关</bold></red>";
                        case VALUABLE -> valuedOff
                                ? "<gray><st>到期提醒：只提醒贵重</st></gray>"
                                : "<gold><bold>到期提醒：只提醒贵重</bold></gold>";
                        case ALL -> "<green><bold>到期提醒：开</bold></green>";
                    },
                    "<gray>物品到期（转公共/销毁）时 " + switch (notify) {
                        case OFF -> "静默处理";
                        case VALUABLE -> valuedOff ? "静默处理（贵重清单已关闭或为空）" : "只提醒贵重物品";
                        case ALL -> "全部提醒";
                    },
                    "<dark_gray>点击切换</dark_gray>");

            boolean destroy = plugin.privateStore().isExpiryDestroy(s.target);
            d[GuiBase.SLOT_EXPIRY] = GuiBase.icon(
                    cfg.icon(destroy ? "expiry-destroy" : "expiry-to-public", destroy ? Material.FIRE_CHARGE : Material.CHEST),
                    destroy ? "<red><bold>到期去向：自动销毁</bold></red>" : "<gold><bold>到期去向：转公共回收站</bold></gold>",
                    "<gray>私人箱到期后 " + (destroy ? "直接销毁" : "转入公共回收站"),
                    "<dark_gray>点击切换</dark_gray>");

            long windowMs = cfg.expiryPreviewMinutes() * 60_000L;
            List<BinEntry> soon = expiringSoon(entries, now, windowMs);
            List<String> previewLore = new ArrayList<>();
            previewLore.add(TextUtil.apply(cfg.msg("preview-lore"), "m", String.valueOf(cfg.expiryPreviewMinutes())));
            if (soon.isEmpty()) {
                previewLore.add(TextUtil.apply(cfg.msg("preview-hover-empty"), "m", String.valueOf(cfg.expiryPreviewMinutes())));
            } else {
                int shown = Math.min(soon.size(), 9);
                for (int i = 0; i < shown; i++) {
                    BinEntry pe = soon.get(i);
                    long left = pe.expireAt() - now;
                    previewLore.add(TextUtil.apply(cfg.msg("preview-hover-line"),
                            "item", ItemNames.describe(pe.item()),
                            "n", String.valueOf(pe.item().getAmount()),
                            "color", expiryColor(left, cfg.privateTtlMs()),
                            "t", TimeUtil.format(left)));
                }
                if (soon.size() > shown) {
                    previewLore.add(TextUtil.apply(cfg.msg("preview-hover-more"), "n", String.valueOf(soon.size() - shown)));
                }
            }
            d[GuiBase.SLOT_PREVIEW] = GuiBase.icon(cfg.icon("preview", Material.SPYGLASS),
                    cfg.msg("preview-name"), previewLore.toArray(new String[0]));
        }

        d[GuiBase.SLOT_TAKE_ALL] = GuiBase.icon(cfg.icon("take-all", Material.LIME_SHULKER_BOX),
                "<green><bold>全部取回</bold></green>",
                "<dark_gray>最旧优先，装不下的跳过</dark_gray>");

        d[GuiBase.SLOT_PREV] = GuiBase.icon(cfg.icon("prev-page", Material.ARROW), "<white>上一页");
        d[GuiBase.SLOT_NEXT] = GuiBase.icon(cfg.icon("next-page", Material.ARROW), "<white>下一页");
        d[GuiBase.SLOT_PAGE] = GuiBase.icon(cfg.icon("page-indicator", Material.PAPER),
                "<white>第 " + (s.page + 1) + "/" + pages + " 页");
        d[GuiBase.SLOT_SWITCH] = GuiBase.icon(cfg.icon("switch-to-public", Material.CHEST),
                "<gold><bold>切到公共回收站</bold></gold>");
        d[GuiBase.SLOT_REFRESH] = GuiBase.icon(cfg.icon("refresh", Material.CLOCK), "<white>刷新");
        d[GuiBase.SLOT_SORT] = GuiBase.icon(cfg.icon("sort", Material.HOPPER),
                "<white>排序：" + GuiBase.sortName(s.sort),
                "<dark_gray>点击切换</dark_gray>");

        GuiBase.apply(s, d);
    }

    static String expiryColor(long left, long ttl) {
        if (ttl <= 0) {
            return "<red>";
        }
        double frac = (double) left / ttl;
        if (frac > 0.5) {
            return "<green>";
        }
        if (frac > 0.2) {
            return "<yellow>";
        }
        return "<red>";
    }

    static void onClick(GuiSession s, int slot, ClickType click) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
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
            PublicGui.open(p);
            return;
        }
        if (slot == GuiBase.SLOT_TOGGLE && s.page == 0) {
            if (!p.getUniqueId().equals(s.target) && !p.hasPermission("fmkit.admin")) {
                TextUtil.msg(p, cfg.prefixed("no-permission"));
                return;
            }
            boolean nowOn = !plugin.privateStore().isCollectEnabled(s.target);
            plugin.privateStore().setCollectEnabled(s.target, nowOn);
            TextUtil.msg(p, cfg.prefixed(nowOn ? "toggle-on" : "toggle-off"));
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_NOTIFY && s.page == 0) {
            if (!p.getUniqueId().equals(s.target) && !p.hasPermission("fmkit.admin")) {
                TextUtil.msg(p, cfg.prefixed("no-permission"));
                return;
            }
            NotifyMode next = plugin.privateStore().notifyMode(s.target).next();
            if (next == NotifyMode.VALUABLE && cfg.valuableItems().isEmpty()) {
                next = NotifyMode.ALL;
            }
            plugin.privateStore().setNotifyMode(s.target, next);
            TextUtil.msg(p, cfg.prefixed(switch (next) {
                case OFF -> "notify-off";
                case VALUABLE -> "notify-valuable";
                case ALL -> "notify-on";
            }));
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_EXPIRY && s.page == 0) {
            if (!p.getUniqueId().equals(s.target) && !p.hasPermission("fmkit.admin")) {
                TextUtil.msg(p, cfg.prefixed("no-permission"));
                return;
            }
            boolean nowDestroy = !plugin.privateStore().isExpiryDestroy(s.target);
            plugin.privateStore().setExpiryDestroy(s.target, nowDestroy);
            TextUtil.msg(p, cfg.prefixed(nowDestroy ? "expiry-mode-destroy" : "expiry-mode-public"));
            render(s);
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        if (slot == GuiBase.SLOT_PREVIEW && s.page == 0) {
            preview(s);
            return;
        }
        if (slot == GuiBase.SLOT_TAKE_ALL) {
            takeAll(s);
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
        if (click == ClickType.SHIFT_RIGHT) {
            destroy(s, id);
        } else if (click.isRightClick()) {
            moveToPublic(s, id);
        } else if (click.isLeftClick()) {
            retrieve(s, id);
        }
    }

    private static void retrieve(GuiSession s, String id) {
        var plugin = s.plugin;
        Player p = s.viewer;
        BinEntry e = plugin.privateStore().takeEntry(s.target, id);
        if (e == null) {
            render(s);
            return;
        }
        if (e.expireAt() <= System.currentTimeMillis()) {
            plugin.privateStore().putBack(s.target, e);
            TextUtil.msg(p, plugin.settings().prefixed("entry-expired"));
            render(s);
            return;
        }
        ItemStack item = e.item();
        int fit = GuiBase.maxFit(p.getInventory(), item);
        if (fit <= 0) {
            plugin.privateStore().putBack(s.target, e);
            TextUtil.msg(p, plugin.settings().prefixed("bag-full"));
            render(s);
            return;
        }
        int full = item.getAmount();
        ItemStack taken = fit >= full ? item : GuiBase.split(item, fit);
        if (fit < full) {
            plugin.privateStore().putBack(s.target,
                    new BinEntry(e.id(), item, e.ownerName(), e.depositAt(), e.expireAt(), e.seq()));
        }
        p.getInventory().addItem(taken);
        TextUtil.msg(p, TextUtil.apply(plugin.settings().prefixed("taken-back"),
                "item", ItemNames.describe(taken) + " ×" + taken.getAmount()));
        GuiBase.sound(p, plugin, Sound.ENTITY_ITEM_PICKUP);
        render(s);
    }

    private static void moveToPublic(GuiSession s, String id) {
        var plugin = s.plugin;
        BinEntry e = plugin.privateStore().takeEntry(s.target, id);
        if (e == null) {
            render(s);
            return;
        }
        if (e.expireAt() <= System.currentTimeMillis()) {
            plugin.privateStore().putBack(s.target, e);
            TextUtil.msg(s.viewer, plugin.settings().prefixed("entry-expired"));
            render(s);
            return;
        }
        plugin.publicStore().add(e.renewedForPublic(plugin.settings().publicTtlMs()));
        TextUtil.msg(s.viewer, TextUtil.apply(plugin.settings().prefixed("moved-to-public"),
                "item", ItemNames.describe(e.item())));
        GuiBase.sound(s.viewer, plugin, Sound.UI_BUTTON_CLICK);
        render(s);
    }

    private static void destroy(GuiSession s, String id) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
        long now = System.currentTimeMillis();
        Pending p = PENDING_DESTROY.get(s.viewer.getUniqueId());
        if (p != null && id.equals(p.id()) && now < p.deadline()) {
            PENDING_DESTROY.remove(s.viewer.getUniqueId());
            BinEntry gone = plugin.privateStore().takeEntry(s.target, id);
            if (gone != null) {
                if (gone.expireAt() <= System.currentTimeMillis()) {
                    plugin.privateStore().putBack(s.target, gone);
                    TextUtil.msg(s.viewer, cfg.prefixed("entry-expired"));
                } else {
                    plugin.getLogger().info("[销毁] 玩家 " + s.viewer.getName() + " 销毁了 "
                            + ItemNames.describe(gone.item()) + " ×" + gone.item().getAmount());
                    TextUtil.msg(s.viewer, TextUtil.apply(cfg.prefixed("destroyed"),
                            "item", ItemNames.describe(gone.item())));
                    GuiBase.sound(s.viewer, plugin, Sound.ENTITY_ITEM_BREAK);
                }
            }
        } else {
            PENDING_DESTROY.put(s.viewer.getUniqueId(),
                    new Pending(id, now + cfg.destroyConfirmSeconds() * 1000L));
            TextUtil.msg(s.viewer, TextUtil.apply(cfg.prefixed("destroy-confirm"),
                    "s", String.valueOf(cfg.destroyConfirmSeconds())));
        }
        render(s);
    }
    /** Entries expiring within the window, most urgent first; the source list is copied, not mutated. */
    static List<BinEntry> expiringSoon(List<BinEntry> source, long now, long windowMs) {
        List<BinEntry> out = new ArrayList<>(source);
        out.removeIf(e -> {
            long left = e.expireAt() - now;
            return left <= 0 || left > windowMs;
        });
        out.sort(Comparator.comparingLong(BinEntry::expireAt).thenComparing(BinEntry::id));
        return out;
    }

    private static void preview(GuiSession s) {
        var plugin = s.plugin;
        var cfg = plugin.settings();
        Player p = s.viewer;
        int minutes = cfg.expiryPreviewMinutes();
        long now = System.currentTimeMillis();
        List<BinEntry> entries = expiringSoon(plugin.privateStore().snapshot(s.target), now, minutes * 60_000L);
        String pre = cfg.msg("prefix");
        if (entries.isEmpty()) {
            TextUtil.msg(p, pre + TextUtil.apply(cfg.msg("preview-empty"), "m", String.valueOf(minutes)));
            GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
            return;
        }
        boolean destroy = plugin.privateStore().isExpiryDestroy(s.target);
        TextUtil.msg(p, pre + TextUtil.apply(cfg.msg("preview-title"), "m", String.valueOf(minutes)));
        int shown = Math.min(entries.size(), 9);
        for (int i = 0; i < shown; i++) {
            BinEntry e = entries.get(i);
            TextUtil.msg(p, pre + TextUtil.apply(cfg.msg("preview-line"),
                    "item", ItemNames.describe(e.item()),
                    "t", TimeUtil.format(e.expireAt() - now),
                    "dest", cfg.msg(destroy ? "preview-dest-destroy" : "preview-dest-public")));
        }
        if (entries.size() > shown) {
            TextUtil.msg(p, pre + TextUtil.apply(cfg.msg("preview-more"),
                    "n", String.valueOf(entries.size() - shown)));
        }
        GuiBase.sound(p, plugin, Sound.UI_BUTTON_CLICK);
    }


    private static void takeAll(GuiSession s) {
        var plugin = s.plugin;
        Player p = s.viewer;
        List<BinEntry> all = plugin.privateStore().snapshot(s.target);
        long now = System.currentTimeMillis();
        all.removeIf(e -> e.expireAt() <= now);
        all.sort(Comparator.comparingLong(BinEntry::depositAt).thenComparing(BinEntry::id));
        int n = 0;
        for (BinEntry peek : all) {
            if (!GuiBase.canFit(p.getInventory(), peek.item())) {
                continue;
            }
            BinEntry taken = plugin.privateStore().takeEntry(s.target, peek.id());
            if (taken == null) {
                continue;
            }
            if (taken.expireAt() <= System.currentTimeMillis()) {
                plugin.privateStore().putBack(s.target, taken);
                continue;
            }
            p.getInventory().addItem(taken.item());
            n++;
        }
        if (n > 0) {
            TextUtil.msg(p, TextUtil.apply(plugin.settings().prefixed("taken-all-private"),
                    "n", String.valueOf(n)));
            GuiBase.sound(p, plugin, Sound.ENTITY_ITEM_PICKUP);
        } else if (!all.isEmpty()) {
            TextUtil.msg(p, plugin.settings().prefixed("bag-full"));
        }
        render(s);
    }
}
