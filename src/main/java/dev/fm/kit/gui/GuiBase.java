package dev.fm.kit.gui;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared layout constants and item helpers for the 54-slot bin pages. */
public final class GuiBase {

    /** Rows 1-4, all 9 columns: page 1 item area (36 slots). */
    public static final int[] PAGE0_SLOTS = {
             9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44};

    /** Rows 0-4, all 9 columns: page 2+ item area (45 slots, no book/buttons). */
    public static final int[] PAGE_N_SLOTS = {
             0,  1,  2,  3,  4,  5,  6,  7,  8,
             9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44};

    public static final int SLOT_TOGGLE = 1;   // private-only recycle switch (top row)
    /** Private-only expiry reminder toggle (top row). */
    public static final int SLOT_NOTIFY = 2;
    public static final int SLOT_BOOK = 4;
    /** Private-only expiry destination toggle: public vs destroy (top row). */
    public static final int SLOT_EXPIRY = 6;
    /** Private-only expiry preview (top row). */
    public static final int SLOT_PREVIEW = 7;
    /** Take-all, bottom row second-from-last, both bins, all pages. */
    public static final int SLOT_TAKE_ALL = 52;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_PAGE = 47;
    public static final int SLOT_SWITCH = 48;
    public static final int SLOT_REFRESH = 49;
    /** Sort cycle, both bins. */
    public static final int SLOT_SORT = 51;
    public static final int SLOT_NEXT = 53;

    /** Top-row filler slots on page 1: private keeps the button slots open, public centers the book. */
    private static final Set<Integer> DARK_BAR_PRIVATE = Set.of(0, 3, 5, 8);
    private static final Set<Integer> DARK_BAR_PUBLIC = Set.of(0, 1, 2, 3, 5, 6, 7, 8);

    public static Set<Integer> darkBarSlots(GuiSession.View view) {
        return switch (view) {
            case PRIVATE -> DARK_BAR_PRIVATE;
            case PUBLIC -> DARK_BAR_PUBLIC;
            case HUB -> Set.of();
        };
    }

    public static int pageCapacity(int page) {
        return page <= 0 ? PAGE0_SLOTS.length : PAGE_N_SLOTS.length;
    }

    public static int pageStart(int page) {
        return page <= 0 ? 0 : PAGE0_SLOTS.length + (page - 1) * PAGE_N_SLOTS.length;
    }

    public static int pageCount(int total) {
        if (total <= PAGE0_SLOTS.length) {
            return 1;
        }
        return 1 + (total - PAGE0_SLOTS.length + PAGE_N_SLOTS.length - 1) / PAGE_N_SLOTS.length;
    }

    public static int[] pageSlots(int page) {
        return page <= 0 ? PAGE0_SLOTS : PAGE_N_SLOTS;
    }

    public static String sortName(GuiSession.Sort sort) {
        return switch (sort) {
            case NEWEST -> "最新优先";
            case OLDEST -> "最旧优先";
            case EXPIRING -> "最先到期";
        };
    }

    /** Re-renders the window every gui.auto-refresh-seconds while it stays open; 0 disables. */
    public static void startAutoRefresh(GuiSession s) {
        int seconds = s.plugin.settings().guiAutoRefreshSeconds();
        if (seconds <= 0) {
            return;
        }
        long period = seconds * 20L;
        s.autoRefresh = s.viewer.getScheduler().runAtFixedRate(s.plugin, t -> {
            if (!s.viewer.isOnline() || s.viewer.getOpenInventory().getTopInventory() != s.inv) {
                t.cancel();
                s.autoRefresh = null;
                return;
            }
            switch (s.view) {
                case HUB -> HubMenu.render(s);
                case PRIVATE -> PrivateGui.render(s);
                case PUBLIC -> PublicGui.render(s);
            }
        }, null, period, period);
    }

    /**
     * Refreshes the live window's title when its text changed. The bin count is
     * baked into the title, so every render has to re-check it; the string
     * compare keeps unchanged frames from re-sending the open packet on each
     * auto-refresh tick.
     *
     * InventoryView#setTitle re-sends the open packet for the *same* container
     * id, so the client keeps the GUI open and a carried cursor stack is never
     * close-flushed. Recreating the inventory and calling openInventory() would
     * allocate a new window id, and the implicit close flushes the carried stack
     * while a click's own cursor write is still pending -- that duplicates a
     * dragged stack.
     */
    public static void retitle(GuiSession s, String want) {
        if (want.equals(s.titleText)) {
            return;
        }
        if (!s.viewer.isOnline()) {
            return;
        }
        InventoryView view = s.viewer.getOpenInventory();
        if (view.getTopInventory() != s.inv) {
            // Not our window yet (open() renders before openInventory) or no longer
            // ours. Leave titleText on the value the client actually holds, so the
            // next render still sends the update instead of assuming it landed.
            return;
        }
        view.setTitle(TextUtil.legacy(want));
        s.titleText = want;
    }

    private GuiBase() {
    }

    public static ItemStack icon(Material mat, String name, String... lore) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.displayName(TextUtil.mini(name));
            if (lore.length > 0) {
                List<Component> lines = new ArrayList<>();
                for (String l : lore) {
                    lines.add(TextUtil.mini(l));
                }
                m.lore(lines);
            }
            is.setItemMeta(m);
        }
        return is;
    }

    /** Decorative filler: empty display name so nothing shows on hover. */
    public static ItemStack pane(Material mat) {
        ItemStack is = new ItemStack(mat);
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            m.displayName(Component.empty());
            is.setItemMeta(m);
        }
        return is;
    }

    /** Entry card: real item + dynamic lore, display name untouched. */
    public static ItemStack card(ItemStack base, List<String> lore) {
        ItemStack is = base.clone();
        ItemMeta m = is.getItemMeta();
        if (m != null) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) {
                lines.add(TextUtil.mini(l));
            }
            m.lore(lines);
            is.setItemMeta(m);
        }
        return is;
    }

    /** How many items of the stack fit into the player's storage slots. */
    public static int maxFit(PlayerInventory inv, ItemStack stack) {
        int max = Math.min(stack.getMaxStackSize(), inv.getMaxStackSize());
        int space = 0;
        for (ItemStack is : inv.getStorageContents()) {
            if (is == null || is.getType().isAir()) {
                space += max;
            } else if (is.isSimilar(stack)) {
                space += Math.max(0, max - is.getAmount());
            }
            if (space >= stack.getAmount()) {
                return stack.getAmount();
            }
        }
        return Math.min(space, stack.getAmount());
    }

    /** Full-fit check before taking an entry into the player inventory. */
    public static boolean canFit(PlayerInventory inv, ItemStack stack) {
        return maxFit(inv, stack) >= stack.getAmount();
    }

    /** Diff-applies a fully rendered frame; only changed slots are re-sent to the client. */
    public static void apply(GuiSession s, ItemStack[] desired) {
        Inventory inv = s.inv;
        for (int i = 0; i < desired.length; i++) {
            if (!Objects.equals(inv.getItem(i), desired[i])) {
                inv.setItem(i, desired[i]);
            }
        }
    }

    /** Take {@code count} items off the stack; the passed stack becomes the remainder. */
    public static ItemStack split(ItemStack stack, int count) {
        ItemStack taken = stack.clone();
        taken.setAmount(count);
        stack.setAmount(stack.getAmount() - count);
        return taken;
    }

    public static void sound(Player p, FmKitPlugin plugin, Sound s) {
        if (plugin.settings().sounds()) {
            p.playSound(p.getLocation(), s, 0.7f, 1.0f);
        }
    }
}
