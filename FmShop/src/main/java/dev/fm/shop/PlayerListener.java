package dev.fm.shop;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps player money off the region threads' critical path.
 *
 * <p>Join warms the cache asynchronously so the first purchase never blocks on
 * disk; quit flushes it so a crash after a session loses nothing.
 */
public final class PlayerListener implements Listener {

    private final FmShopPlugin plugin;

    public PlayerListener(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.data().loadAsync(event.getPlayer().getUniqueId(), d -> {
            // A brand-new account is dirty on creation; persist it now so the
            // starting balance is fixed at first join.
            if (d.dirty()) {
                plugin.data().save(event.getPlayer().getUniqueId(), d);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var d = plugin.data().get(event.getPlayer().getUniqueId());
        if (d != null && d.dirty()) {
            plugin.data().save(event.getPlayer().getUniqueId(), d);
        }
    }
}
