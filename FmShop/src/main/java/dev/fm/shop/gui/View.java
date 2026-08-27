package dev.fm.shop.gui;

import dev.fm.shop.FmShopPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * A chest-menu screen owned by one player.
 *
 * <p>The view IS the inventory holder, so a click event resolves back to its
 * screen with an {@code instanceof} check - no server-wide slot bookkeeping to
 * leak when a player disconnects mid-menu.
 *
 * <p>Every method here runs on the owning player's region thread: clicks arrive
 * there, and the refresh ticker hops through the player's entity scheduler.
 */
public abstract class View implements InventoryHolder {

    protected final FmShopPlugin plugin;
    protected final Player player;
    private Inventory inventory;

    protected View(FmShopPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    protected abstract Component title();

    protected abstract int size();

    /** Fills the inventory; called on open and on every auto-refresh. */
    public abstract void render();

    public abstract void click(int slot, ClickType type);

    public final void open() {
        inventory = Bukkit.createInventory(this, size(), title());
        render();
        player.openInventory(inventory);
        plugin.gui().track(this);
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public final Player player() {
        return player;
    }

    /** True while this view is the player's open top inventory. */
    public final boolean live() {
        return inventory != null
                && player.isOnline()
                && player.getOpenInventory().getTopInventory() == inventory;
    }
}
