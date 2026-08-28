package dev.fm.shop.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu item builder.
 *
 * <p>Every name and lore line is forced non-italic: Minecraft italicises custom
 * display names by default, which makes a whole menu look like placeholder
 * text.
 */
public final class Icons {

    private Icons() {
    }

    public static ItemStack of(Material mat, String nameMini, List<String> loreMini) {
        return of(new ItemStack(mat), nameMini, loreMini);
    }

    /**
     * Decorates a caller-supplied stack - typically a {@code PriceEntry#probe()}
     * - instead of building one from a bare material, so an enchanted-book row
     * keeps its stored enchantment tooltip and glint.
     *
     * <p>The argument is cloned before any meta is applied: probes are compared
     * with {@code isSimilar} elsewhere, and mutating the caller's instance would
     * corrupt every later match.
     *
     * <p>Only the attribute and unbreakable lines are hidden, via
     * {@code TOOLTIP_DISPLAY}. The stored enchantment is deliberately left visible
     * - on a book it IS the row's identity, and hiding it would make every book
     * row in the menu look identical.
     */
    public static ItemStack of(ItemStack base, String nameMini, List<String> loreMini) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) {
            return it;
        }
        if (nameMini != null) {
            meta.displayName(plain(nameMini));
        }
        if (loreMini != null && !loreMini.isEmpty()) {
            List<Component> lore = new ArrayList<>(loreMini.size());
            for (String line : loreMini) {
                lore.add(plain(line));
            }
            meta.lore(lore);
        }
        it.setItemMeta(meta);
        it.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                        DataComponentTypes.UNBREAKABLE));
        return it;
    }

    public static ItemStack of(Material mat, String nameMini) {
        return of(mat, nameMini, null);
    }

    /** Same icon carrying a stack size, used for quantity previews. */
    public static ItemStack of(Material mat, int amount, String nameMini, List<String> loreMini) {
        return of(new ItemStack(mat), amount, nameMini, loreMini);
    }

    /**
     * Quantity-preview variant of {@link #of(ItemStack, String, List)}. The
     * amount clamps to the base stack's own limit: an {@code ENCHANTED_BOOK}
     * stacks to 1, not 64.
     */
    public static ItemStack of(ItemStack base, int amount, String nameMini, List<String> loreMini) {
        ItemStack it = of(base, nameMini, loreMini);
        it.setAmount(Math.max(1, Math.min(it.getMaxStackSize(), amount)));
        return it;
    }

    /** MiniMessage fragment rendered without the default italic styling. */
    public static Component plain(String mini) {
        return dev.fm.shop.util.TextUtil.mini(mini).decoration(TextDecoration.ITALIC, false);
    }
}
