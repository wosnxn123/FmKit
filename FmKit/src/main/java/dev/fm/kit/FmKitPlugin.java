package dev.fm.kit;

import dev.fm.kit.bin.BinExpiryTask;
import dev.fm.kit.bin.PrivateBinStore;
import dev.fm.kit.bin.PublicBinStore;
import dev.fm.kit.bin.BinLogger;
import dev.fm.kit.cleaner.DeathDropHandler;
import dev.fm.kit.cleaner.SweepScheduler;
import dev.fm.kit.command.FmKitAdminCommand;
import dev.fm.kit.command.FmKitCommand;
import dev.fm.kit.gui.GuiListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FmKitPlugin extends JavaPlugin {

    private static FmKitPlugin instance;

    private Settings settings;
    private PrivateBinStore privateStore;
    private PublicBinStore publicStore;
    private SweepScheduler sweep;
    private BinExpiryTask expiry;
    private BinLogger binLogger;

    public static FmKitPlugin instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        settings = new Settings(this);

        privateStore = new PrivateBinStore(this);
        publicStore = new PublicBinStore(this);
        binLogger = new BinLogger(this);
        publicStore.load();
        privateStore.loadAll();
        getLogger().info("已加载私人箱 " + privateStore.bins().size() + " 位玩家 / "
                + privateStore.totalEntries() + " 条，公共箱 " + publicStore.size() + " 条");

        getServer().getPluginManager().registerEvents(new DeathDropHandler(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent ev) {
                privateStore.recordName(ev.getPlayer().getUniqueId(), ev.getPlayer().getName());
            }
        }, this);

        sweep = new SweepScheduler(this);
        sweep.applyConfig();
        expiry = new BinExpiryTask(this);
        expiry.start();

        FmKitCommand fc = new FmKitCommand(this);
        PluginCommand fmkit = getCommand("fmkit");
        fmkit.setExecutor(fc);
        fmkit.setTabCompleter(fc);
        FmKitAdminCommand ac = new FmKitAdminCommand(this);
        PluginCommand admin = getCommand("fmkitadmin");
        admin.setExecutor(ac);
        admin.setTabCompleter(ac);
    }

    @Override
    public void onDisable() {
        if (sweep != null) {
            sweep.stopTasks();
        }
        if (expiry != null) {
            expiry.stop();
        }
        if (privateStore != null) {
            privateStore.shutdown();
            privateStore.saveAllSync();
        }
        if (publicStore != null) {
            publicStore.shutdown();
            publicStore.saveSync();
        }
        instance = null;
    }

    public void reload() {
        reloadConfig();
        settings.load();
        sweep.applyConfig();
        expiry.stop();
        expiry.start();
    }

    public Settings settings() {
        return settings;
    }

    public PrivateBinStore privateStore() {
        return privateStore;
    }

    public PublicBinStore publicStore() {
        return publicStore;
    }

    public SweepScheduler sweep() {
        return sweep;
    }

    public BinLogger binLogger() {
        return binLogger;
    }
}
