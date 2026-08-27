package dev.fm.shop.store;

import dev.fm.shop.util.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The price table, parsed from config.yml.
 *
 * <p>Rows are keyed by {@link Material} name, and unknown names are collected
 * instead of aborting the load: a config written for 1.21.x can name materials
 * that a later drop renamed or removed (and 26.2 adds SULFUR/CINNABAR families
 * that older servers lack). {@code /fsa doctor} lists them so the operator can
 * fix the file, while every valid row keeps working.
 */
public final class PriceCatalog {

    private final Map<Material, PriceEntry> entries = new EnumMap<>(Material.class);
    private final Map<String, List<Material>> byCategory = new LinkedHashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<String> unknown = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    /** Defaults applied to rows that omit the dynamic-pricing block. */
    private record Dyn(boolean on, int stepBp, int floorBp, int ceilBp, int recoverBpPerHour) {
    }

    public void load(ConfigurationSection root) {
        entries.clear();
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
            PriceEntry e = new PriceEntry(mat, cat, buy, sell,
                    Math.max(0, row.getInt("daily-buy", 0)),
                    Math.max(0, row.getInt("daily-sell", 0)),
                    d.on(), d.stepBp(), d.floorBp(), d.ceilBp(), d.recoverBpPerHour());
            entries.put(mat, e);
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(mat);
        }
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

    private Dyn readDyn(ConfigurationSection sec, Dyn def) {
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

    public PriceEntry get(Material mat) {
        return entries.get(mat);
    }

    public boolean has(Material mat) {
        return entries.containsKey(mat);
    }

    /** Pulls a row out of the live table (strict-mode doctor enforcement). */
    public boolean remove(Material mat) {
        if (entries.remove(mat) == null) {
            return false;
        }
        for (List<Material> list : byCategory.values()) {
            list.remove(mat);
        }
        return true;
    }

    public int size() {
        return entries.size();
    }

    public List<Category> categories() {
        return categories;
    }

    /** Materials of one tab, in config order. */
    public List<Material> items(String categoryId) {
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
     * Resolves user input to a priced material: exact id, then unique prefix of
     * the material name, so {@code /fmshop price sulfur_sp} finds SULFUR_SPIKE.
     */
    public Material match(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Material exact = Material.matchMaterial(input.trim());
        if (exact != null && entries.containsKey(exact)) {
            return exact;
        }
        String needle = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        Material hit = null;
        for (Material m : entries.keySet()) {
            if (m.name().startsWith(needle)) {
                if (hit != null) {
                    return null;
                }
                hit = m;
            }
        }
        if (hit != null) {
            return hit;
        }
        for (Material m : entries.keySet()) {
            if (m.name().contains(needle)) {
                if (hit != null) {
                    return null;
                }
                hit = m;
            }
        }
        return hit;
    }
}
