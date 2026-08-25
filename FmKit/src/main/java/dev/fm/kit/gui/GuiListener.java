package dev.fm.kit.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Routes clicks on our inventories; blocks item movement into them. */
public final class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiSession s)) {
            return;
        }
        if (e.getClickedInventory() == e.getView().getTopInventory()) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player)) {
                return;
            }
            switch (s.view) {
                case HUB -> HubMenu.onClick(s, e.getSlot());
                case PRIVATE -> PrivateGui.onClick(s, e.getSlot(), e.getClick());
                case PUBLIC -> PublicGui.onClick(s, e.getSlot(), e.getClick());
            }
        } else if (e.getClickedInventory() != null) {
            // Player inventory side: only block movements into the GUI.
            if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || e.getAction() == InventoryAction.HOTBAR_SWAP
                    || e.getAction() == InventoryAction.COLLECT_TO_CURSOR
                    || e.getClick() == ClickType.NUMBER_KEY) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiSession)) {
            return;
        }
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
