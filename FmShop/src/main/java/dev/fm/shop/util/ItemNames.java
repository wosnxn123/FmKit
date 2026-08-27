package dev.fm.shop.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item naming for shop text.
 *
 * <p>Shop rows are keyed by {@link Material}, so the name shown to a player is
 * always the vanilla one. Rather than shipping a Chinese name table that would
 * rot on every version bump, prices render the material's translation key as a
 * MiniMessage {@code <lang:...>} tag: the client resolves it in its own locale,
 * so a zh_CN client sees 铁锭 and an en_US client sees Iron Ingot with no
 * per-item config.
 */
public final class ItemNames {

    private ItemNames() {
    }

    /** MiniMessage fragment that the client renders in its own language. */
    public static String mini(Material mat) {
        return "<lang:" + mat.translationKey() + ">";
    }

    /** Plain, locale-independent name for console output and audit lines. */
    public static String plain(Material mat) {
        return mat.name().toLowerCase().replace('_', ' ');
    }

    /** Plain description of a concrete stack, e.g. {@code iron ingot ×32}. */
    public static String describe(ItemStack item) {
        if (item == null) {
            return "?";
        }
        String name = base(item);
        int n = item.getAmount();
        return n > 1 ? name + " ×" + n : name;
    }

    private static String base(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            String custom = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            if (!custom.isBlank()) {
                return custom;
            }
        }
        return plain(item.getType());
    }
}
