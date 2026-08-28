package dev.fm.shop.store;

import dev.fm.shop.util.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The price table, parsed from prices.yml.
 *
 * <p>Rows are keyed by {@link PriceEntry#id()} rather than by {@link Material},
 * because one material can back many rows: every enchanted book is
 * {@code ENCHANTED_BOOK}. A material-to-rows index is kept alongside so an
 * inventory stack can be resolved back to its row in one step - see
 * {@link #match(ItemStack)}.
 *
 * <p>Unknown material names are collected instead of aborting the load: a config
 * written for 1.21.x can name materials that a later drop renamed or removed
 * (and 26.2 adds SULFUR/CINNABAR families that older servers lack).
 * {@code /fsa doctor} lists them so the operator can fix the file, while every
 * valid row keeps working.
 */
public final class PriceCatalog {

    private final Map<String, PriceEntry> entries = new LinkedHashMap<>();
    /** Reverse index for resolving a held stack; most lists hold one row. */
    private final Map<Material, List<PriceEntry>> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, List<String>> byCategory = new LinkedHashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<String> unknown = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    /** Defaults applied to rows that omit the dynamic-pricing block. */
    record Dyn(boolean on, int stepBp, int floorBp, int ceilBp, int recoverBpPerHour) {
    }

    public void load(ConfigurationSection root) {
        entries.clear();
        byMaterial.clear();
        byCategory.clear();
        categories.clear();
        unknown.clear();
        problems.clear();
        if (root == null) {
            return;
        }
        Dyn def = readDyn(root.getConfigurationSection("dynamic-defaults"),
                new Dyn(false, 25, 5_000, 20_000, 500));
        loadCategories(root.getConfigurationSection("categories"));
        loadItems(root.getConfigurationSection("items"), def);
        for (PriceEntry e : EnchantedBooks.expand(
                root.getConfigurationSection("enchanted-books"), def, problems)) {
            add(e);
        }
        categories.sort(Comparator.comparingInt(Category::order));
        for (Category c : categories) {
            byCategory.computeIfAbsent(c.id(), k -> new ArrayList<>());
        }
    }

    private void loadCategories(ConfigurationSection sec) {
        if (sec == null) {
            return;
        }
        int i = 0;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) {
                continue;
            }
            Material icon = Material.matchMaterial(c.getString("icon", "STONE"));
            if (icon == null || !icon.isItem()) {
                problems.add("分类 " + id + " 的图标无效：" + c.getString("icon"));
                icon = Material.STONE;
            }
            categories.add(new Category(id.toLowerCase(Locale.ROOT),
                    c.getString("name", id),
                    icon,
                    c.getInt("order", i)));
            i++;
        }
    }

    private void loadItems(ConfigurationSection sec, Dyn def) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection row = sec.getConfigurationSection(key);
            if (row == null) {
                problems.add("物品 " + key + " 不是配置节");
                continue;
            }
            Material mat = Material.matchMaterial(key);
            if (mat == null) {
                unknown.add(key);
                continue;
            }
            if (!mat.isItem()) {
                problems.add(key + " 不是可持有物品，已跳过");
                continue;
            }
            long buy = cents(row, "buy", key);
            long sell = cents(row, "sell", key);
            if (buy == 0 && sell == 0) {
                problems.add(key + " 买卖价均为 0，已跳过");
                continue;
            }
            String cat = row.getString("category", "misc").toLowerCase(Locale.ROOT);
            Dyn d = readDyn(row.getConfigurationSection("dynamic"), def);
            boolean unlock = row.getBoolean("unlock-by-sell", false);
            int lifetime = Math.max(0, row.getInt("lifetime-sell", 0));
            if (unlock && sell == 0) {
                problems.add(key + " 设了 unlock-by-sell 但不回收，将永远无法解锁");
            }
            if (unlock && buy == 0) {
                problems.add(key + " 设了 unlock-by-sell 但不出售，解锁没有意义");
            }
            if (lifetime > 0 && sell == 0) {
                problems.add(key + " 设了 lifetime-sell 但不回收，该上限不会生效");
            }
            add(new PriceEntry(ItemKey.of(mat), cat, buy, sell,
                    Math.max(0, row.getInt("daily-buy", 0)),
                    Math.max(0, row.getInt("daily-sell", 0)),
                    unlock, lifetime,
                    d.on(), d.stepBp(), d.floorBp(), d.ceilBp(), d.recoverBpPerHour()));
        }
    }

    /** Registers a row in the id table, the material index and its category. */
    private void add(PriceEntry e) {
        String id = e.id();
        if (entries.putIfAbsent(id, e) != null) {
            problems.add("重复的商品 " + id + "，后一条已忽略");
            return;
        }
        byMaterial.computeIfAbsent(e.material(), k -> new ArrayList<>(1)).add(e);
        byCategory.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(id);
    }

    private long cents(ConfigurationSection row, String path, String key) {
        if (!row.isSet(path)) {
            return 0;
        }
        long v = Money.ofDouble(row.getDouble(path, 0));
        if (v < 0) {
            problems.add(key + " 的 " + path + " 价格无效：" + row.get(path));
            return 0;
        }
        return v;
    }

    static Dyn readDyn(ConfigurationSection sec, Dyn def) {
        if (sec == null) {
            return def;
        }
        int floor = sec.getInt("floor-percent", def.floorBp() / 100) * 100;
        int ceil = sec.getInt("ceil-percent", def.ceilBp() / 100) * 100;
        if (floor <= 0) {
            floor = 100;
        }
        if (ceil < floor) {
            ceil = floor;
        }
        return new Dyn(sec.getBoolean("enabled", def.on()),
                Math.max(0, sec.getInt("step-bp", def.stepBp())),
                floor,
                ceil,
                Math.max(0, sec.getInt("recover-bp-per-hour", def.recoverBpPerHour())));
    }

    public PriceEntry get(String id) {
        return id == null ? null : entries.get(id);
    }

    /** The bare-material row for {@code mat}, ignoring any variant rows. */
    public PriceEntry plain(Material mat) {
        return mat == null ? null : entries.get(mat.name());
    }

    /**
     * The row that {@code stack} trades as, or null if the shop does not deal in
     * it. At most one row can match: {@link ItemKey#matches} is exact, so a
     * plain row and a variant row of the same material never both accept the
     * same stack.
     */
    public PriceEntry match(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        List<PriceEntry> rows = byMaterial.get(stack.getType());
        if (rows == null) {
            return null;
        }
        for (PriceEntry e : rows) {
            if (e.key().matches(stack)) {
                return e;
            }
        }
        return null;
    }

    public boolean has(String id) {
        return entries.containsKey(id);
    }

    /** Pulls a row out of the live table (strict-mode doctor enforcement). */
    public boolean remove(String id) {
        PriceEntry gone = entries.remove(id);
        if (gone == null) {
            return false;
        }
        List<PriceEntry> rows = byMaterial.get(gone.material());
        if (rows != null) {
            rows.remove(gone);
            if (rows.isEmpty()) {
                byMaterial.remove(gone.material());
            }
        }
        for (List<String> list : byCategory.values()) {
            list.remove(id);
        }
        return true;
    }

    public int size() {
        return entries.size();
    }

    public List<Category> categories() {
        return categories;
    }

    /** Row ids of one tab, in config order. */
    public List<String> items(String categoryId) {
        return byCategory.getOrDefault(categoryId, List.of());
    }

    /** Config keys that no longer resolve to a Material (renamed/removed). */
    public List<String> unknown() {
        return unknown;
    }

    /** Non-fatal config complaints surfaced by /fsa doctor. */
    public List<String> problems() {
        return problems;
    }

    public Iterable<PriceEntry> all() {
        return entries.values();
    }

    /**
     * Resolves free-text command input to a row: exact id (case-insensitive),
     * then the plain row of a named material, then a unique prefix or substring
     * of an id. So {@code /fmshop price sulfur_sp} finds SULFUR_SPIKE and
     * {@code /fmshop price sharpness/5} finds the Sharpness V book, while a
     * bare {@code sharpness} is ambiguous across five levels and resolves to
     * nothing rather than to an arbitrary one.
     */
    public PriceEntry match(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String needle = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        PriceEntry hit = entries.get(needle);
        if (hit != null) {
            return hit;
        }
        Material exact = Material.matchMaterial(input.trim());
        if (exact != null) {
            hit = entries.get(exact.name());
            if (hit != null) {
                return hit;
            }
        }
        String id = unique(needle, true);
        if (id == null) {
            id = unique(needle, false);
        }
        return id == null ? null : entries.get(id);
    }

    /** The single id starting with (or containing) {@code needle}, else null. */
    private String unique(String needle, boolean prefix) {
        String hit = null;
        for (String id : entries.keySet()) {
            if (prefix ? id.startsWith(needle) : id.contains(needle)) {
                if (hit != null) {
                    return null;
                }
                hit = id;
            }
        }
        return hit;
    }
}
