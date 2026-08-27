package dev.fm.shop.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
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
        ItemStack it = new ItemStack(mat);
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
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack of(Material mat, String nameMini) {
        return of(mat, nameMini, null);
    }

    /** Same icon carrying a stack size, used for quantity previews. */
    public static ItemStack of(Material mat, int amount, String nameMini, List<String> loreMini) {
        ItemStack it = of(mat, nameMini, loreMini);
        it.setAmount(Math.max(1, Math.min(mat.getMaxStackSize(), amount)));
        return it;
    }

    /** MiniMessage fragment rendered without the default italic styling. */
    public static Component plain(String mini) {
        return dev.fm.shop.util.TextUtil.mini(mini).decoration(TextDecoration.ITALIC, false);
    }
}
