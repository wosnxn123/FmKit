package dev.fm.kit.cleaner;

import dev.fm.kit.FmKitPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Death drops behave exactly like player-thrown items: vanilla ground items
 * are spawned, tagged with the deceased as thrower, so the next sweep routes
 * them into the deceased's private bin if nobody picked them up.
 */
public final class DeathDropHandler implements Listener {

    private final FmKitPlugin plugin;

    public DeathDropHandler(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        var s = plugin.settings();
        if (!s.collectOnDeath()) {
            return;
        }
        if (!s.collectEnabled()) {
            return;
        }
        if (e.getKeepInventory()) {
            return;
        }
        Player p = e.getEntity();
        if (!plugin.privateStore().isCollectEnabled(p.getUniqueId())) {
            return;
        }
        List<ItemStack> drops = e.getDrops();
        if (drops.isEmpty()) {
            return;
        }
        List<ItemStack> taken = new ArrayList<>(drops);
        drops.clear();
        Location loc = p.getLocation();
        for (ItemStack stack : taken) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Item it = p.getWorld().dropItemNaturally(loc, stack);
            it.setThrower(p.getUniqueId());
        }
    }
}
