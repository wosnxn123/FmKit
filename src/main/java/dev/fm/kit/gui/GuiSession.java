package dev.fm.kit.gui;

import dev.fm.kit.FmKitPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One open menu session; the InventoryHolder lets listeners find it back. */
public final class GuiSession implements InventoryHolder {

    public enum View { HUB, PRIVATE, PUBLIC }

    public enum Sort { NEWEST, OLDEST, EXPIRING }

    public final FmKitPlugin plugin;
    public final Player viewer;
    public View view;
    /** PRIVATE view: bin owner (may differ from viewer for admin browsing). */
    public UUID target;
    public int page;
    /** Page count of the last render; bounds NEXT clicks. */
    public int pages = 1;
    public Sort sort = Sort.NEWEST;
    public Inventory inv;
    /**
     * MiniMessage source of the title last sent to the client. The count in the
     * title is dynamic, so render() compares against this and retitles in place
     * only when the text actually changed.
     */
    public String titleText = "";
    /** Rendered entry ids, parallel to the content slots actually filled. */
    public final List<String> slotToId = new ArrayList<>();
    /** Running auto-refresh task for this session; self-cancels once the window is gone. */
    public ScheduledTask autoRefresh;

    public GuiSession(FmKitPlugin plugin, Player viewer, View view) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.view = view;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
