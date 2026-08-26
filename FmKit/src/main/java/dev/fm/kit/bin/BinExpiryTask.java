package dev.fm.kit.bin;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.util.ItemNames;
import dev.fm.kit.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic expiry processing. Runs on the global region scheduler (all mutations touch the
 * shared public store, so one thread owns the whole pass). Also issues one-shot countdown
 * warnings a configurable number of seconds before each private entry expires.
 */
public final class BinExpiryTask {

    private final FmKitPlugin plugin;
    /** Private entry ids already warned this server life; pruned as entries disappear. */
    private final Set<String> warnedIds = new HashSet<>();

    public BinExpiryTask(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask task;

    public void start() {
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> run(),
                plugin.settings().expiryScanInterval() * 20L, plugin.settings().expiryScanInterval() * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void run() {
        long now = System.currentTimeMillis();
        Set<Material> valuable = plugin.settings().valuableItems();
        Set<String> liveIds = new HashSet<>();
        for (Map.Entry<UUID, PrivateBin> en : plugin.privateStore().bins().entrySet()) {
            PrivateBin bin = en.getValue();
            List<BinEntry> moved;
            List<BinEntry> destroyed;
            synchronized (bin) {
                moved = move(bin, now);
                destroyed = destroy(bin, now);
                if (!moved.isEmpty() || !destroyed.isEmpty()) {
                    plugin.privateStore().saveAsync(en.getKey());
                }
                for (BinEntry e : bin.entries()) {
                    liveIds.add(e.id());
                }
                notify(en.getKey(), moved, destroyed, valuable);
                warn(en.getKey(), bin, now, valuable);
                plugin.binLogger().privateExpire(en.getKey(), moved, destroyed);
            }
        }
        warnedIds.retainAll(liveIds);
        List<BinEntry> purged = plugin.publicStore().removeExpired(now);
        if (!purged.isEmpty()) {
            plugin.binLogger().publicExpire(purged);
        }
        plugin.binLogger().flushWindows();
    }

    /** Pull expired entries whose destination is the public bin. */
    private List<BinEntry> move(PrivateBin bin, long now) {
        List<BinEntry> out = new ArrayList<>();
        if (bin.expiryDestroy()) {
            return out;
        }
        for (Iterator<BinEntry> it = bin.entries().iterator(); it.hasNext(); ) {
            BinEntry e = it.next();
            if (e.expireAt() <= now) {
                it.remove();
                plugin.publicStore().add(e.renewedForPublic(plugin.settings().publicTtlMs()));
                out.add(e);
            }
        }
        return out;
    }

    /** Pull expired entries whose destination is destruction. */
    private List<BinEntry> destroy(PrivateBin bin, long now) {
        List<BinEntry> out = new ArrayList<>();
        if (!bin.expiryDestroy()) {
            return out;
        }
        for (Iterator<BinEntry> it = bin.entries().iterator(); it.hasNext(); ) {
            BinEntry e = it.next();
            if (e.expireAt() <= now) {
                it.remove();
                out.add(e);
            }
        }
        return out;
    }

    private void notify(UUID uuid, List<BinEntry> moved, List<BinEntry> destroyed, Set<Material> valuable) {
        if (moved.isEmpty() && destroyed.isEmpty()) {
            return;
        }
        NotifyMode mode = plugin.privateStore().notifyMode(uuid);
        if (mode == NotifyMode.OFF) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            return;
        }
        boolean chat = plugin.settings().moveNotifyChat();
        int maxShown = plugin.settings().notifyMaxShown();
        List<BinEntry> movedShown = filter(mode, moved, valuable);
        if (!movedShown.isEmpty()) {
            deliver(p, chat, TextUtil.apply(plugin.settings().prefixed("move-notify"),
                    "items", summarize(movedShown, maxShown)));
        }
        List<BinEntry> destroyedShown = filter(mode, destroyed, valuable);
        if (!destroyedShown.isEmpty()) {
            deliver(p, chat, TextUtil.apply(plugin.settings().prefixed("expiry-destroyed"),
                    "items", summarize(destroyedShown, maxShown)));
        }
    }

    /** One-shot countdown warn for entries inside the warn window. */
    private void warn(UUID uuid, PrivateBin bin, long now, Set<Material> valuable) {
        int seconds = plugin.settings().expiryWarnSeconds();
        NotifyMode mode = plugin.privateStore().notifyMode(uuid);
        if (seconds <= 0 || bin.entries().isEmpty() || mode == NotifyMode.OFF) {
            return;
        }
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            return;
        }
        boolean chat = plugin.settings().moveNotifyChat();
        boolean toDestroy = bin.expiryDestroy();
        long windowMs = seconds * 1000L;
        for (BinEntry e : bin.entries()) {
            long left = e.expireAt() - now;
            if (left <= 0 || left > windowMs) {
                continue;
            }
            if (mode == NotifyMode.VALUABLE && !valuable.contains(e.item().getType())) {
                continue;
            }
            if (!warnedIds.add(e.id())) {
                continue;
            }
            deliver(p, chat, TextUtil.apply(plugin.settings().prefixed(toDestroy ? "expiry-warn-destroy" : "expiry-warn-public"),
                    "item", ItemNames.describe(e.item()), "s", String.valueOf(Math.max(1, left / 1000))));
        }
    }

    private static List<BinEntry> filter(NotifyMode mode, List<BinEntry> list, Set<Material> valuable) {
        if (list.isEmpty() || mode == NotifyMode.ALL) {
            return list;
        }
        List<BinEntry> out = new ArrayList<>();
        for (BinEntry e : list) {
            if (valuable.contains(e.item().getType())) {
                out.add(e);
            }
        }
        return out;
    }

    static String summarize(List<BinEntry> entries, int maxShown) {
        if (maxShown <= 0) {
            return entries.size() + " 件物品";
        }
        int shown = Math.min(entries.size(), maxShown);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(ItemNames.describe(entries.get(i).item()));
        }
        if (entries.size() > shown) {
            sb.append(" 等 ").append(entries.size()).append(" 件");
        }
        return sb.toString();
    }

    private static void deliver(Player p, boolean chat, String text) {
        if (chat) {
            TextUtil.send(p, text);
        } else {
            TextUtil.action(p, text);
        }
    }
}
