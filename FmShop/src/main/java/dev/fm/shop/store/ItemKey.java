package dev.fm.shop.store;

import dev.fm.shop.util.TextUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Locale;

/**
 * What one price row trades: a material, optionally carrying a single stored
 * enchantment (an enchanted book).
 *
 * <p>The shop used to key rows on {@link Material} alone, which cannot express
 * "Sharpness V book" as distinct from "Mending book" - both are
 * {@code ENCHANTED_BOOK}. Splitting identity out of {@link PriceEntry} lets one
 * material back many rows while every lookup, quota and ledger keys on
 * {@link #id()}.
 *
 * <p>Matching is left to {@link ItemStack#isSimilar}: a probe built here carries
 * exactly the meta the row means, so a book with Sharpness IV, a book with
 * Sharpness V <em>and</em> Unbreaking III, and a renamed book all correctly fail
 * to match the Sharpness V row. That is the same rule plain rows already relied
 * on to reject NBT-bearing items, so no new matching logic is needed.
 *
 * @param material the base material
 * @param enchant  stored enchantment, or null for a plain row
 * @param level    stored level; meaningless (0) when {@code enchant} is null
 */
public record ItemKey(Material material, Enchantment enchant, int level) {

    /** Separator in {@link #id()}; never '.', which is a YAML path separator. */
    private static final char SEP = '/';

    public static ItemKey of(Material material) {
        return new ItemKey(material, null, 0);
    }

    public static ItemKey book(Enchantment enchant, int level) {
        return new ItemKey(Material.ENCHANTED_BOOK, enchant, level);
    }

    /** Whether this row trades a bare material rather than a variant. */
    public boolean plain() {
        return enchant == null;
    }

    /**
     * Persistence and lookup key.
     *
     * <p>A plain row's id is exactly {@code material.name()}, which is the
     * format already written in player saves and market state, so existing files
     * keep loading unchanged. Variant rows append the enchantment and level.
     */
    public String id() {
        if (enchant == null) {
            return material.name();
        }
        return material.name() + SEP + enchantId(enchant.getKey()) + SEP + level;
    }

    /**
     * A one-item stack of exactly what this row trades.
     *
     * <p>Freshly built on each call: the result is handed to inventory code that
     * may set its amount, and a shared instance would let one caller's resize
     * corrupt every later comparison.
     */
    public ItemStack probe() {
        ItemStack it = new ItemStack(material);
        if (enchant != null) {
            it.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(enchant, level, true));
        }
        return it;
    }

    /** Whether {@code stack} is exactly what this row trades. */
    public boolean matches(ItemStack stack) {
        return stack != null && stack.getType() == material && stack.isSimilar(probe());
    }

    /**
     * Client-localised name fragment: the vanilla enchantment and level keys, so
     * a Chinese client reads 锋利 V without the plugin shipping a name table.
     *
     * <p>The enchantment name comes from {@link Enchantment#description()} rather
     * than {@code translationKey()}: a data pack may give an enchantment a literal
     * name instead of a translation key, and only the component form can carry
     * both. Serialising it back to MiniMessage yields {@code <lang:...>} for every
     * vanilla enchantment and the literal text for the rest.
     */
    public String mini() {
        if (enchant == null) {
            return "<lang:" + material.translationKey() + ">";
        }
        String name = TextUtil.serialize(enchant.description());
        return enchant.getMaxLevel() > 1 ? name + " <lang:enchantment.level." + level + ">" : name;
    }

    /** Locale-independent name for console output and audit lines. */
    public String plainName() {
        String base = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (enchant == null) {
            return base;
        }
        return base + " " + enchant.getKey().value().replace('_', ' ')
                + (enchant.getMaxLevel() > 1 ? " " + level : "");
    }

    /** {@code minecraft:sharpness} to SHARPNESS; other namespaces keep theirs. */
    private static String enchantId(NamespacedKey key) {
        String raw = NamespacedKey.MINECRAFT.equals(key.getNamespace())
                ? key.value()
                : key.getNamespace() + "_" + key.value();
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            out.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        return out.toString();
    }
}
