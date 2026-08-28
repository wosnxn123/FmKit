package dev.fm.kit.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Human-readable item description for chat messages.
 * Priority: custom display name (plain text, MiniMessage-safe) &gt; server-localized
 * name &gt; cleaned material name. Appends " ×N" when the stack holds more than one.
 */
public final class ItemNames {

    private ItemNames() {
    }

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
        String localized = item.getI18NDisplayName();
        if (localized != null && !localized.isBlank()) {
            return localized;
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }
}
