package dev.fm.shop.economy;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.util.Money;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Server-owned pot for collected fees when {@code fees.destination: tax-pool}.
 *
 * <p>Kept as its own tiny file so the operator can read or reset it without
 * touching player data. With the default {@code void} sink this stays at zero
 * and costs nothing.
 */
public final class TaxPool {

    private final FmShopPlugin plugin;
    private final File file;
    private long cents;
    private long lifetime;
    private boolean dirty;

    public TaxPool(FmShopPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tax.yml");
    }

    public synchronized long balance() {
        return cents;
    }

    /** Total ever collected, for /fsa status. */
    public synchronized long lifetime() {
        return lifetime;
    }

    public synchronized void add(long amount) {
        if (amount <= 0) {
            return;
        }
        cents = Money.add(cents, amount);
        lifetime = Money.add(lifetime, amount);
        dirty = true;
    }

    /** Withdraws for an admin payout; false when the pot is short. */
    public synchronized boolean take(long amount) {
        if (amount <= 0 || cents < amount) {
            return false;
        }
        cents -= amount;
        dirty = true;
        return true;
    }

    public synchronized void load() {
        cents = 0;
        lifetime = 0;
        dirty = false;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("tax.yml 读取失败：" + ex.getMessage());
            return;
        }
        cents = Math.max(0, y.getLong("balance"));
        lifetime = Math.max(cents, y.getLong("lifetime"));
    }

    /** Atomic write; no-op when nothing changed. */
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("balance", cents);
        y.set("lifetime", lifetime);
        File tmp = new File(file.getParentFile(), "tax.yml.tmp");
        try {
            y.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("tax.yml 保存失败：" + ex.getMessage());
        }
    }
}
