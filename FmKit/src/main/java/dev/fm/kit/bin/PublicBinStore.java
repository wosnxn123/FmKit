package dev.fm.kit.bin;

import dev.fm.kit.FmKitPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * The single global public bin. Persisted as plugins/FmKit/bins/public.yml.
 * All methods synchronize on this store.
 */
public final class PublicBinStore {

    private final FmKitPlugin plugin;
    private final List<BinEntry> entries = new ArrayList<>();
    /** Arrival order: deposit time first, then entity age within one sweep round. */
    private static final Comparator<BinEntry> BY_ARRIVAL =
            Comparator.comparingLong(BinEntry::depositAt).thenComparingLong(BinEntry::seq);

    public PublicBinStore(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "bins/public.yml");
    }

    public synchronized void load() {
        File f = file();
        if (!f.exists()) {
            return;
        }
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(f);
            var section = y.getConfigurationSection("entries");
            if (section != null) {
                for (String id : section.getKeys(false)) {
                    String base = "entries." + id;
                    ItemStack item = y.getItemStack(base + ".item");
                    if (item == null) {
                        continue;
                    }
                    entries.add(new BinEntry(id, item, y.getString(base + ".owner-name"),
                            y.getLong(base + ".deposit-at"), y.getLong(base + ".expire-at")));
                }
            }
            entries.sort(BY_ARRIVAL);
            int purged = removeExpired(System.currentTimeMillis());
            if (purged > 0) {
                plugin.getLogger().info("启动清理：移除 " + purged + " 条过期公共条目");
                saveSync();
            }
        } catch (Exception ex) {
            File bak = new File(f.getParentFile(), f.getName() + ".bak-" + System.currentTimeMillis());
            plugin.getLogger().warning("公共箱存档损坏，已隔离: " + bak.getName() + " (" + ex.getMessage() + ")");
            f.renameTo(bak);
        }
    }

    public synchronized void add(BinEntry e) {
        boolean newEntry = BinMerge.merge(entries, e.item(), e.ownerName(), e.depositAt(), e.expireAt(),
                g -> Objects.equals(g.ownerName(), e.ownerName()));
        if (newEntry) {
            entries.sort(BY_ARRIVAL);
            int max = plugin.settings().publicMaxEntries();
            if (max > 0 && entries.size() > max) {
                entries.remove(0);
            }
        }
        saveAsync();
    }

    public synchronized BinEntry take(String id) {
        for (Iterator<BinEntry> it = entries.iterator(); it.hasNext(); ) {
            BinEntry e = it.next();
            if (e.id().equals(id)) {
                it.remove();
                saveAsync();
                return e;
            }
        }
        return null;
    }

    public synchronized void putBack(BinEntry e) {
        entries.add(e);
        entries.sort(BY_ARRIVAL);
        saveAsync();
    }

    public synchronized List<BinEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long oldestDeposit() {
        return entries.isEmpty() ? -1 : entries.get(0).depositAt();
    }

    public synchronized int removeExpired(long now) {
        int removed = 0;
        for (Iterator<BinEntry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().expireAt() <= now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            saveAsync();
        }
        return removed;
    }

    public synchronized int clear() {
        int n = entries.size();
        entries.clear();
        if (n > 0) {
            saveAsync();
        }
        return n;
    }

    private YamlConfiguration toYaml() {
        YamlConfiguration y = new YamlConfiguration();
        for (BinEntry e : entries) {
            String base = "entries." + e.id();
            y.set(base + ".item", e.item());
            y.set(base + ".owner-name", e.ownerName());
            y.set(base + ".deposit-at", e.depositAt());
            y.set(base + ".expire-at", e.expireAt());
        }
        return y;
    }

    public synchronized void saveAsync() {
        YamlConfiguration y = toYaml();
        File f = file();
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            try {
                y.save(f);
            } catch (IOException ex) {
                plugin.getLogger().warning("保存失败 " + f + ": " + ex.getMessage());
            }
        });
    }

    public synchronized void saveSync() {
        try {
            toYaml().save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("保存失败 public: " + ex.getMessage());
        }
    }
}
