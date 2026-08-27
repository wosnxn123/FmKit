package dev.fm.shop;

import dev.fm.shop.audit.AuditLog;
import dev.fm.shop.cmd.AdminCommand;
import dev.fm.shop.cmd.ShopCommand;
import dev.fm.shop.economy.Balances;
import dev.fm.shop.economy.FmEconomy;
import dev.fm.shop.economy.TaxPool;
import dev.fm.shop.gui.GuiListener;
import dev.fm.shop.gui.ShopGui;
import dev.fm.shop.store.DataStore;
import dev.fm.shop.store.MarketState;
import dev.fm.shop.store.PriceCatalog;
import dev.fm.shop.store.PriceDoctor;
import dev.fm.shop.tx.TxEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Plugin entry point: owns every subsystem and the order they start and stop in.
 *
 * <p>Start order matters. {@link Settings} comes first because everything else
 * reads it; the price table loads before the doctor can judge it; the GUI ticker
 * starts last so no menu can open against a half-built catalog.
 *
 * <p>Stop order is the reverse, with one rule: menus close before the data store
 * shuts down. A menu whose click handler is gone hands out its own icons.
 */
public final class FmShopPlugin extends JavaPlugin {

    private static final long FLUSH_MINUTES = 5;

    private Settings settings;
    private PriceCatalog prices;
    private MarketState market;
    private DataStore data;
    private Balances balances;
    private TaxPool tax;
    private TxEngine tx;
    private AuditLog audit;
    private ShopGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("prices.yml", false);

        settings = new Settings(this);
        settings.load();

        prices = new PriceCatalog();
        loadPrices();

        market = new MarketState(this);
        market.load();

        data = new DataStore(this);
        balances = new Balances(this);
        tax = new TaxPool(this);
        tax.load();
        audit = new AuditLog(this);
        audit.prune();
        tx = new TxEngine(this);
        gui = new ShopGui(this);

        doctor(true);

        getServer().getServicesManager().register(FmEconomy.class, balances, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        bind("fmshop", new ShopCommand(this));
        bind("fmshopadmin", new AdminCommand(this));

        gui.startTicker();
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> flush(),
                FLUSH_MINUTES, FLUSH_MINUTES, TimeUnit.MINUTES);

        getSLF4JLogger().info("已启用：{} 件商品，{} 个分类，货币「{}」",
                prices.size(), prices.categories().size(), settings.currency());
    }

    @Override
    public void onDisable() {
        if (gui != null) {
            gui.closeAll();
        }
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        getServer().getServicesManager().unregisterAll(this);
        if (market != null) {
            market.write(market.snapshot());
        }
        if (tax != null) {
            tax.save();
        }
        if (data != null) {
            data.close();
        }
        if (audit != null) {
            audit.close();
        }
    }

    /**
     * Rereads config.yml and prices.yml and re-runs the doctor.
     *
     * <p>Balances and market state are deliberately untouched: a reload is a
     * config operation, and re-reading live money from disk would roll back
     * whatever happened since the last flush.
     */
    public void reload() {
        reloadConfig();
        settings.load();
        prices = new PriceCatalog();
        loadPrices();
        doctor(false);
    }

    /** Periodic and shutdown flush of everything that lives outside memory. */
    public void flush() {
        data.saveDirty();
        if (market.dirty()) {
            market.write(market.snapshot());
        }
        tax.save();
    }

    /** Single source of truth for the price-table path; admin edits write here. */
    public File pricesFile() {
        return new File(getDataFolder(), "prices.yml");
    }

    private void loadPrices() {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(pricesFile());
        } catch (IOException | InvalidConfigurationException ex) {
            getSLF4JLogger().error("prices.yml 读取失败，商店将为空：{}", ex.getMessage());
            return;
        }
        prices.load(y);
    }

    /**
     * Runs the arbitrage checks. On startup a strict table drops offending rows;
     * a reload only reports, because pulling items out from under players
     * mid-session is worse than a warning the operator already saw at boot.
     *
     * <p>Only ERROR findings are printed line by line: those are closed loops
     * the shop funds by itself. The soft findings depend on ingredients the shop
     * does not sell, run into the dozens on a vanilla recipe registry, and would
     * bury the real ones at every boot, so they are collapsed into one counted
     * line and left to {@code /fsa doctor} to spell out.
     */
    private void doctor(boolean enforce) {
        List<PriceDoctor.Finding> findings = PriceDoctor.run(prices);
        int warned = 0;
        int noted = 0;
        for (PriceDoctor.Finding f : findings) {
            switch (f.severity()) {
                case ERROR -> getSLF4JLogger().error("价格表：{}", f.text());
                case WARN -> warned++;
                case INFO -> noted++;
            }
        }
        if (warned > 0) {
            getSLF4JLogger().warn("价格表：{} 条疑似套利（材料商店不出售，需人工判断），/fsa doctor 看明细", warned);
        }
        if (noted > 0) {
            getSLF4JLogger().info("价格表：{} 条说明，/fsa doctor 看明细", noted);
        }
        if (enforce && settings.doctorStrict()) {
            int pulled = PriceDoctor.enforce(prices, findings);
            if (pulled > 0) {
                getSLF4JLogger().warn("严格模式：已下架 {} 件存在套利风险的商品", pulled);
            }
        }
    }

    private void bind(String name, Object handler) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getSLF4JLogger().error("plugin.yml 缺少指令 {}", name);
            return;
        }
        cmd.setExecutor((org.bukkit.command.CommandExecutor) handler);
        if (handler instanceof org.bukkit.command.TabCompleter tc) {
            cmd.setTabCompleter(tc);
        }
    }

    public Settings settings() {
        return settings;
    }

    public PriceCatalog prices() {
        return prices;
    }

    public MarketState market() {
        return market;
    }

    public DataStore data() {
        return data;
    }

    public FmEconomy economy() {
        return balances;
    }

    public TaxPool tax() {
        return tax;
    }

    public TxEngine tx() {
        return tx;
    }

    public AuditLog audit() {
        return audit;
    }

    public ShopGui gui() {
        return gui;
    }
}
