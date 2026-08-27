package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Routes inventory events to the {@link View} that owns them.
 *
 * <p>Menu inventories are never real storage, so every click is cancelled before
 * anything else runs - including clicks in the player's own inventory while a
 * menu is open, which is how shift-click would otherwise shove items into a
 * menu and lose them.
 */
public final class GuiListener implements Listener {

    private final FmShopPlugin plugin;

    public GuiListener(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof View view)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || !p.equals(view.player())) {
            return;
        }
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        try {
            view.click(event.getSlot(), event.getClick());
        } catch (RuntimeException ex) {
            plugin.getSLF4JLogger().error("菜单点击处理失败", ex);
            p.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof View view) {
            plugin.gui().forget(view.player());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.gui().forget(event.getPlayer());
    }
}
