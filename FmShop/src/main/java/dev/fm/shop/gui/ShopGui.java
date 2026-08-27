package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of open menus plus the ticker that keeps their prices honest.
 *
 * <p>Dynamic prices move whenever anyone trades, so an open menu goes stale on
 * its own. The ticker runs on the global region scheduler but hands each redraw
 * to the owning player's entity scheduler: on Folia an inventory may only be
 * touched from the region that owns the player.
 */
public final class ShopGui {

    private final FmShopPlugin plugin;
    private final Set<View> open = ConcurrentHashMap.newKeySet();

    public ShopGui(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void openHub(Player p) {
        new HubView(plugin, p).open();
    }

    public void openSellAll(Player p) {
        new SellAllView(plugin, p).open();
    }

    void track(View v) {
        open.add(v);
    }

    /** Called from the close event; also drops views left by a quit. */
    public void forget(Player p) {
        open.removeIf(v -> v.player().equals(p) || !v.live());
    }

    public int openCount() {
        return open.size();
    }

    /** Starts the price ticker; no-op when {@code gui.auto-refresh-seconds} is 0. */
    public void startTicker() {
        int seconds = plugin.settings().guiAutoRefreshSeconds();
        if (seconds <= 0) {
            return;
        }
        long ticks = Math.max(20L, seconds * 20L);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), ticks, ticks);
    }

    private void tick() {
        open.removeIf(v -> !v.player().isOnline());
        for (View v : open) {
            v.player().getScheduler().run(plugin, task -> {
                if (v.live()) {
                    v.render();
                } else {
                    open.remove(v);
                }
            }, null);
        }
    }

    /**
     * Closes every menu on disable. Without this, a reload leaves inventories
     * whose click handler is gone - and every icon in them becomes a free item.
     */
    public void closeAll() {
        for (View v : open) {
            try {
                if (v.live()) {
                    v.player().closeInventory();
                }
            } catch (IllegalStateException | UnsupportedOperationException ex) {
                // Wrong region thread during shutdown; the server closes the
                // inventory itself as the player disconnects.
                plugin.getSLF4JLogger().debug("菜单关闭跳过：{}", ex.getMessage());
            }
        }
        open.clear();
    }
}
