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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The single global public bin. Persisted as plugins/FmKit/bins/public.yml.
 * All methods synchronize on this store.
 */
public final class PublicBinStore {

    private final FmKitPlugin plugin;
    private final List<BinEntry> entries = new ArrayList<>();
    /** Arrival order: deposit time, then entity age within one sweep round, then stable id
     *  (private-origin entries carry seq 0, so the id keeps storage order deterministic). */
    private static final Comparator<BinEntry> BY_ARRIVAL =
            Comparator.comparingLong(BinEntry::depositAt).thenComparingLong(BinEntry::seq)
                    .thenComparing(BinEntry::id);
    /** Ordered single-thread writer: public.yml saves never interleave. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fmkit-public-io");
        t.setDaemon(true);
        return t;
    });

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
            List<BinEntry> purged = removeExpired(System.currentTimeMillis());
            if (!purged.isEmpty()) {
                plugin.getLogger().info("启动清理：移除 " + purged.size() + " 条过期公共条目");
                plugin.binLogger().publicExpire(purged);
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
                BinEntry dropped = entries.remove(0);
                plugin.binLogger().publicOverflow(dropped, max);
            }
        }
        int capMax = plugin.settings().publicMaxEntries();
        if (capMax <= 0 || entries.size() < capMax) {
            plugin.binLogger().publicBelowCap();
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

    public synchronized List<BinEntry> removeExpired(long now) {
        List<BinEntry> removed = new ArrayList<>();
        for (Iterator<BinEntry> it = entries.iterator(); it.hasNext(); ) {
            BinEntry e = it.next();
            if (e.expireAt() <= now) {
                it.remove();
                removed.add(e);
            }
        }
        if (!removed.isEmpty()) {
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
        io.execute(() -> {
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

    /** Drain the writer before the final synchronous save in onDisable. */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("公共箱写入器未在 10 秒内排空");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
