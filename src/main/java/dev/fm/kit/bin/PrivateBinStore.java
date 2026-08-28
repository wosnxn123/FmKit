package dev.fm.kit.bin;

import dev.fm.kit.FmKitPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * All players' private bins. Persisted as plugins/FmKit/bins/private/&lt;uuid&gt;.yml.
 * Mutations synchronize on the owning PrivateBin; file writes go through one ordered
 * single-thread executor, so saves of the same player file never interleave.
 */
public final class PrivateBinStore {

    private final FmKitPlugin plugin;
    private final Map<UUID, PrivateBin> bins = new ConcurrentHashMap<>();
    private final Map<UUID, String> knownNames = new ConcurrentHashMap<>();
    /** Ordered single-thread writer: saves for one player file never interleave. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fmkit-store-io");
        t.setDaemon(true);
        return t;
    });

    public PrivateBinStore(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    private File dir() {
        File d = new File(plugin.getDataFolder(), "bins/private");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private File file(UUID uuid) {
        return new File(dir(), uuid + ".yml");
    }

    public void loadAll() {
        File[] files = dir().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            String name = f.getName();
            try {
                UUID uuid = UUID.fromString(name.substring(0, name.length() - 4));
                YamlConfiguration y = new YamlConfiguration();
                y.load(f);
                bins.put(uuid, fromYaml(uuid, y));
            } catch (IllegalArgumentException | InvalidConfigurationException | IOException ex) {
                File bak = new File(f.getParentFile(), name + ".bak-" + System.currentTimeMillis());
                plugin.getLogger().warning("私人箱存档损坏，已隔离: " + name + " -> " + bak.getName() + " (" + ex.getMessage() + ")");
                f.renameTo(bak);
            }
        }
    }

    private PrivateBin fromYaml(UUID uuid, YamlConfiguration y) {
        Object notifyRaw = y.get("settings.expiry-notify");
        NotifyMode notifyMode = notifyRaw == null
                ? plugin.settings().moveNotifyDefault() : NotifyMode.fromValue(notifyRaw);
        PrivateBin bin = new PrivateBin(uuid,
                y.getBoolean("settings.collect-enabled", plugin.settings().collectDefault()),
                notifyMode,
                y.getBoolean("settings.expiry-destroy", plugin.settings().expiryDestroyDefault()));
        var section = y.getConfigurationSection("entries");
        if (section != null) {
            long now = System.currentTimeMillis();
            for (String id : section.getKeys(false)) {
                String base = "entries." + id;
                ItemStack item = y.getItemStack(base + ".item");
                if (item == null) {
                    continue;
                }
                long deposit = y.getLong(base + ".deposit-at", now);
                long expire = y.getLong(base + ".expire-at", deposit + plugin.settings().privateTtlMs());
                String ownerName = y.getString(base + ".owner-name");
                if (ownerName != null) {
                    knownNames.putIfAbsent(uuid, ownerName);
                }
                bin.entries().add(new BinEntry(id, item, ownerName, deposit, expire));
            }
        }
        return bin;
    }

    public PrivateBin get(UUID uuid) {
        return bins.computeIfAbsent(uuid, u -> new PrivateBin(u, plugin.settings().collectDefault(),
                plugin.settings().moveNotifyDefault(), plugin.settings().expiryDestroyDefault()));
    }

    public boolean isCollectEnabled(UUID uuid) {
        PrivateBin b = bins.get(uuid);
        return b != null ? b.collectEnabled() : plugin.settings().collectDefault();
    }

    public void setCollectEnabled(UUID uuid, boolean v) {
        get(uuid).setCollectEnabled(v);
        saveAsync(uuid);
    }

    public NotifyMode notifyMode(UUID uuid) {
        PrivateBin b = bins.get(uuid);
        return b != null ? b.notifyMode() : plugin.settings().moveNotifyDefault();
    }

    public void setNotifyMode(UUID uuid, NotifyMode v) {
        get(uuid).setNotifyMode(v);
        saveAsync(uuid);
    }

    public boolean isExpiryDestroy(UUID uuid) {
        PrivateBin b = bins.get(uuid);
        return b != null ? b.expiryDestroy() : plugin.settings().expiryDestroyDefault();
    }

    public void setExpiryDestroy(UUID uuid, boolean v) {
        get(uuid).setExpiryDestroy(v);
        saveAsync(uuid);
    }

    public void deposit(UUID uuid, String ownerName, ItemStack item) {
        if (ownerName != null) {
            knownNames.put(uuid, ownerName);
        }
        PrivateBin bin = get(uuid);
        synchronized (bin) {
            long now = System.currentTimeMillis();
            boolean newEntry = BinMerge.merge(bin.entries(), item, ownerName, now,
                    now + plugin.settings().privateTtlMs(), g -> true);
            if (newEntry) {
                int max = plugin.settings().privateMaxEntries();
                if (max > 0 && bin.entries().size() > max) {
                    BinEntry oldest = bin.entries().remove(0);
                    plugin.binLogger().privateOverflow(uuid, oldest, bin.entries().size() + 1, max);
                    plugin.publicStore().add(oldest.renewedForPublic(plugin.settings().publicTtlMs()));
                }
            }
            int capMax = plugin.settings().privateMaxEntries();
            if (capMax <= 0 || bin.entries().size() < capMax) {
                plugin.binLogger().privateBelowCap(uuid);
            }
        }
        saveAsync(uuid);
    }

    public BinEntry takeEntry(UUID uuid, String id) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return null;
        }
        synchronized (bin) {
            for (Iterator<BinEntry> it = bin.entries().iterator(); it.hasNext(); ) {
                BinEntry e = it.next();
                if (e.id().equals(id)) {
                    it.remove();
                    saveAsync(uuid);
                    return e;
                }
            }
        }
        return null;
    }

    public BinEntry peekEntry(UUID uuid, String id) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return null;
        }
        synchronized (bin) {
            for (BinEntry e : bin.entries()) {
                if (e.id().equals(id)) {
                    return e;
                }
            }
        }
        return null;
    }

    public void putBack(UUID uuid, BinEntry e) {
        PrivateBin bin = get(uuid);
        synchronized (bin) {
            bin.entries().add(e);
        }
        saveAsync(uuid);
    }

    public List<BinEntry> snapshot(UUID uuid) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return new ArrayList<>();
        }
        synchronized (bin) {
            return new ArrayList<>(bin.entries());
        }
    }

    public int clear(UUID uuid) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return 0;
        }
        synchronized (bin) {
            int n = bin.entries().size();
            bin.entries().clear();
            if (n > 0) {
                saveAsync(uuid);
            }
            return n;
        }
    }

    public int size(UUID uuid) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return 0;
        }
        synchronized (bin) {
            return bin.entries().size();
        }
    }

    public int totalEntries() {
        int t = 0;
        for (PrivateBin b : bins.values()) {
            synchronized (b) {
                t += b.entries().size();
            }
        }
        return t;
    }

    public Map<UUID, PrivateBin> bins() {
        return bins;
    }

    /** In-memory UUID→name cache fed by joins, deposits and stored bins. */
    public void recordName(UUID uuid, String name) {
        if (uuid != null && name != null) {
            knownNames.put(uuid, name);
        }
    }

    public String knownName(UUID uuid) {
        return knownNames.get(uuid);
    }

    public void saveAsync(UUID uuid) {
        PrivateBin bin = bins.get(uuid);
        if (bin == null) {
            return;
        }
        YamlConfiguration y;
        synchronized (bin) {
            y = toYaml(bin);
        }
        File f = file(uuid);
        io.execute(() -> {
            try {
                y.save(f);
            } catch (IOException ex) {
                plugin.getLogger().warning("保存失败 " + f + ": " + ex.getMessage());
            }
        });
    }

    private YamlConfiguration toYaml(PrivateBin bin) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("settings.collect-enabled", bin.collectEnabled());
        y.set("settings.expiry-notify", bin.notifyMode().name());
        y.set("settings.expiry-destroy", bin.expiryDestroy());
        for (BinEntry e : bin.entries()) {
            String base = "entries." + e.id();
            y.set(base + ".item", e.item());
            y.set(base + ".owner-name", e.ownerName());
            y.set(base + ".deposit-at", e.depositAt());
            y.set(base + ".expire-at", e.expireAt());
        }
        return y;
    }

    public void saveAllSync() {
        for (Map.Entry<UUID, PrivateBin> en : bins.entrySet()) {
            YamlConfiguration y;
            synchronized (en.getValue()) {
                y = toYaml(en.getValue());
            }
            try {
                y.save(file(en.getKey()));
            } catch (IOException ex) {
                plugin.getLogger().warning("保存失败 " + en.getKey() + ": " + ex.getMessage());
            }
        }
    }

    /** Drain the writer before the final synchronous saves in onDisable. */
    public void shutdown() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("私人箱写入器未在 10 秒内排空");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
