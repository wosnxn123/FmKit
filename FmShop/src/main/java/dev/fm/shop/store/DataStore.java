package dev.fm.shop.store;

import dev.fm.shop.FmShopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Per-player YAML persistence under plugins/FmShop/data/&lt;uuid&gt;.yml.
 * Single-threaded async writes; corruption is quarantined to .bak-&lt;ts&gt;.
 *
 * <p>Balances are written through on every mutation rather than on a timer:
 * a crash between a purchase and a periodic flush would hand the item over for
 * free.
 */
public final class DataStore {

    private static final int CACHE_CAP = 256;

    private final FmShopPlugin plugin;
    private final File dir;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FmShop-IO");
        t.setDaemon(true);
        return t;
    });

    public DataStore(FmShopPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "data");
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /** Cache lookup; loaded players only (join loads online players). */
    public PlayerData get(UUID id) {
        return cache.get(id);
    }

    /** Cached entry, or a freshly loaded one. Blocks on IO; admin paths only. */
    public PlayerData loadSync(UUID id) {
        PlayerData cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        PlayerData d = read(id);
        cache.put(id, d);
        evictIfNeeded();
        return d;
    }

    /** Async load with the callback resumed on the global region scheduler. */
    public void loadAsync(UUID id, Consumer<PlayerData> done) {
        PlayerData cached = cache.get(id);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        io.execute(() -> {
            PlayerData d = read(id);
            cache.putIfAbsent(id, d);
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                PlayerData cur = cache.get(id);
                done.accept(cur != null ? cur : d);
                evictIfNeeded();
            });
        });
    }

    /** Queue an async save of the current state. */
    public void save(UUID id, PlayerData d) {
        YamlConfiguration y = toYaml(d);
        d.clean();
        io.execute(() -> write(id, y));
    }

    /** Flush every dirty cached player without blocking the caller. */
    public void saveDirty() {
        for (Map.Entry<UUID, PlayerData> en : cache.entrySet()) {
            if (en.getValue().dirty()) {
                save(en.getKey(), en.getValue());
            }
        }
    }

    /** Drain pending writes, then save every cached player synchronously. */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Map.Entry<UUID, PlayerData> en : cache.entrySet()) {
            write(en.getKey(), toYaml(en.getValue()));
        }
    }

    public int loadedCount() {
        return cache.size();
    }

    /** Every known player file, for /fsa status totals. */
    public int fileCount() {
        String[] names = dir.list((d, n) -> n.endsWith(".yml"));
        return names == null ? 0 : names.length;
    }

    private File file(UUID id) {
        return new File(dir, id + ".yml");
    }

    private PlayerData read(UUID id) {
        File f = file(id);
        if (!f.exists()) {
            // Left dirty on purpose: the first flush writes the file, so the
            // account is pinned at today's starting balance instead of drifting
            // when the operator later edits currency.starting-balance.
            PlayerData fresh = new PlayerData();
            fresh.balance(plugin.settings().startingBalance());
            return fresh;
        }
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(f);
            return fromYaml(y);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            quarantine(f, ex);
            return new PlayerData();
        }
    }

    private PlayerData fromYaml(YamlConfiguration y) {
        PlayerData d = new PlayerData();
        d.restore(Math.max(0, y.getLong("balance")),
                y.getLong("day"),
                Math.max(0, y.getLong("total-spent")),
                Math.max(0, y.getLong("total-earned")));
        readCounters(y.getConfigurationSection("bought"), true, d);
        readCounters(y.getConfigurationSection("sold"), false, d);
        d.clean();
        return d;
    }

    private void readCounters(ConfigurationSection sec, boolean buy, PlayerData d) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            int n = sec.getInt(key);
            if (mat != null && n > 0) {
                d.restoreCounter(buy, mat, n);
            }
        }
    }

    private YamlConfiguration toYaml(PlayerData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("balance", d.balance());
        y.set("day", d.day());
        y.set("total-spent", d.totalSpent());
        y.set("total-earned", d.totalEarned());
        for (Map.Entry<String, Integer> e : d.boughtSnapshot().entrySet()) {
            y.set("bought." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : d.soldSnapshot().entrySet()) {
            y.set("sold." + e.getKey(), e.getValue());
        }
        return y;
    }

    private void write(UUID id, YamlConfiguration y) {
        File f = file(id);
        File tmp = new File(dir, id + ".yml.tmp");
        try {
            y.save(tmp);
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().warning("存档失败 " + f + "：" + ex.getMessage());
        }
    }

    private void quarantine(File f, Exception ex) {
        File bak = new File(dir, f.getName() + ".bak-" + System.currentTimeMillis());
        plugin.getLogger().warning("存档损坏 " + f + "（" + ex.getMessage() + "），隔离为 " + bak.getName());
        if (!f.renameTo(bak)) {
            plugin.getLogger().warning("隔离失败：" + f);
        }
    }

    /** Drop clean cached entries of offline players once the cap is exceeded. */
    private void evictIfNeeded() {
        if (cache.size() <= CACHE_CAP) {
            return;
        }
        for (UUID id : new ArrayList<>(cache.keySet())) {
            if (cache.size() <= CACHE_CAP) {
                break;
            }
            PlayerData d = cache.get(id);
            if (Bukkit.getPlayer(id) == null && d != null && !d.dirty()) {
                cache.remove(id);
            }
        }
    }
}
