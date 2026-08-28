package dev.fm.shop.store;

import dev.fm.shop.util.Money;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Expands the {@code enchanted-books} config block into one price row per
 * (enchantment, level) pair - 128 rows on vanilla 26.2.
 *
 * <p>Written out by hand that table would rot: prices must stay consistent with
 * each other, and a game update that adds an enchantment (26.2 added
 * {@code lunge}) would silently leave a hole. So prices come from a curve, and
 * the curve's rarity term is read from vanilla's own data rather than invented
 * here:
 *
 * <pre>
 *   sell = base-sell x level^level-exponent x rarity^rarity-exponent x curse?
 *   buy  = sell x buy-multiplier
 * </pre>
 *
 * <p>{@code rarity} defaults to {@link Enchantment#getAnvilCost()}, which vanilla
 * sets to 1, 2, 4 or 8 - it is already a value multiplier, it is exactly the
 * inverse of {@link Enchantment#getWeight()} (10, 5, 2, 1), and it is the only
 * rarity signal the API exposes that means "worth more" rather than merely
 * "appears less often". That single term fixes what a level-only curve gets
 * badly wrong: Silk Touch, Infinity and Channeling are max-level 1 and not
 * treasure, so a level curve prices them like Sharpness I, while vanilla marks
 * all three anvil-cost 8.
 *
 * <p>Where vanilla's scarcity disagrees with player demand, the operator
 * overrides {@code rarity} per enchantment - see the block in prices.yml. That
 * keeps the level curve intact for the overridden rows, which a flat {@code sell}
 * override would flatten.
 *
 * <p>Rows are emitted in a stable alphabetical order because registry iteration
 * order is not specified, and an unstable order would reshuffle the shop's pages
 * between restarts.
 */
final class EnchantedBooks {

    private EnchantedBooks() {
    }

    static List<PriceEntry> expand(ConfigurationSection sec, PriceCatalog.Dyn def, List<String> problems) {
        if (sec == null || !sec.getBoolean("enabled", false)) {
            return List.of();
        }
        double baseSell = sec.getDouble("base-sell", 24);
        double levelExp = sec.getDouble("level-exponent", 1.7);
        double rarityExp = sec.getDouble("rarity-exponent", 1);
        double buyMul = sec.getDouble("buy-multiplier", 6);
        double curseMul = sec.getDouble("curse-multiplier", 0.25);
        String category = sec.getString("category", "enchants").toLowerCase(Locale.ROOT);
        boolean unlock = sec.getBoolean("unlock-by-sell", true);
        int dailyBuy = Math.max(0, sec.getInt("daily-buy", 0));
        int dailySell = Math.max(0, sec.getInt("daily-sell", 0));
        int lifetimeSell = Math.max(0, sec.getInt("lifetime-sell", 1));
        int levelCap = Math.max(0, sec.getInt("max-level", 0));
        PriceCatalog.Dyn dyn = PriceCatalog.readDyn(sec.getConfigurationSection("dynamic"), def);

        if (baseSell <= 0) {
            problems.add("enchanted-books 的 base-sell 必须大于 0，附魔书未加载");
            return List.of();
        }
        if (buyMul <= 1) {
            problems.add("enchanted-books 的 buy-multiplier 应大于 1，否则买价不高于卖价，可无限刷钱");
        }
        if (dailySell == 0 && lifetimeSell == 0) {
            problems.add("enchanted-books 没有任何回收上限：治好的僵尸村民图书管理员能用 1 颗绿宝石"
                    + "换一本可交易附魔书（原版 tradeable 标签，含修补），卖给商店即为无上限刷钱。"
                    + "建议 lifetime-sell: 1");
        }

        Set<Enchantment> skip = resolveAll(sec.getStringList("exclude"), problems, "exclude");
        Map<String, ConfigurationSection> overrides = readOverrides(
                sec.getConfigurationSection("overrides"), problems);

        List<Enchantment> all = new ArrayList<>();
        for (Enchantment ench : registry()) {
            if (!skip.contains(ench)) {
                all.add(ench);
            }
        }
        all.sort(Comparator.comparing(e -> e.getKey().toString()));

        List<PriceEntry> out = new ArrayList<>(all.size() * 3);
        for (Enchantment ench : all) {
            ConfigurationSection wide = overrides.get(ench.getKey().toString());
            int max = ench.getMaxLevel();
            if (levelCap > 0) {
                max = Math.min(max, levelCap);
            }
            if (wide != null && wide.isSet("max-level")) {
                max = Math.min(ench.getMaxLevel(), Math.max(1, wide.getInt("max-level")));
            }
            double rarity = rarity(ench, wide);
            double curse = ench.isCursed() ? curseMul : 1;
            for (int level = 1; level <= max; level++) {
                ConfigurationSection narrow = overrides.get(ench.getKey() + "/" + level);
                double units = baseSell * Math.pow(level, levelExp)
                        * Math.pow(rarity, rarityExp) * curse;
                long sell = Money.ofDouble(pick(narrow, wide, "sell", units));
                long buy = Money.ofDouble(pick(narrow, wide, "buy", units * buyMul));
                if (buy == 0 && sell == 0) {
                    continue;
                }
                out.add(new PriceEntry(ItemKey.book(ench, level), category, buy, sell,
                        (int) pick(narrow, wide, "daily-buy", dailyBuy),
                        (int) pick(narrow, wide, "daily-sell", dailySell),
                        flag(narrow, wide, "unlock-by-sell", unlock),
                        (int) pick(narrow, wide, "lifetime-sell", lifetimeSell),
                        dyn.on(), dyn.stepBp(), dyn.floorBp(), dyn.ceilBp(), dyn.recoverBpPerHour()));
            }
        }
        return out;
    }

    /**
     * Value tier for one enchantment: the operator's {@code rarity} override, else
     * vanilla's anvil cost.
     *
     * <p>Clamped to at least 1 because a data pack is free to declare 0, and a
     * zero rarity would collapse that enchantment's whole level curve to nothing.
     */
    private static double rarity(Enchantment ench, ConfigurationSection wide) {
        if (wide != null && wide.isSet("rarity")) {
            return Math.max(0.01, wide.getDouble("rarity"));
        }
        return Math.max(1, ench.getAnvilCost());
    }

    /** Level-scoped override wins over enchantment-scoped, which wins over the curve. */
    private static double pick(ConfigurationSection narrow, ConfigurationSection wide,
                               String path, double fallback) {
        if (narrow != null && narrow.isSet(path)) {
            return Math.max(0, narrow.getDouble(path));
        }
        if (wide != null && wide.isSet(path)) {
            return Math.max(0, wide.getDouble(path));
        }
        return fallback;
    }

    private static boolean flag(ConfigurationSection narrow, ConfigurationSection wide,
                                String path, boolean fallback) {
        if (narrow != null && narrow.isSet(path)) {
            return narrow.getBoolean(path);
        }
        if (wide != null && wide.isSet(path)) {
            return wide.getBoolean(path);
        }
        return fallback;
    }

    /**
     * Indexes the overrides block by canonical key, accepting both
     * {@code SHARPNESS} (all levels) and {@code SHARPNESS/5} (one level).
     */
    private static Map<String, ConfigurationSection> readOverrides(ConfigurationSection sec,
                                                                   List<String> problems) {
        if (sec == null) {
            return Map.of();
        }
        Map<String, ConfigurationSection> out = new HashMap<>();
        for (String key : sec.getKeys(false)) {
            ConfigurationSection row = sec.getConfigurationSection(key);
            if (row == null) {
                problems.add("enchanted-books.overrides." + key + " 不是配置节");
                continue;
            }
            int slash = key.lastIndexOf('/');
            String name = slash < 0 ? key : key.substring(0, slash);
            String tail = slash < 0 ? null : key.substring(slash + 1);
            Enchantment ench = resolve(name);
            if (ench == null) {
                problems.add("enchanted-books.overrides 中的附魔无法识别：" + key);
                continue;
            }
            if (tail == null) {
                out.put(ench.getKey().toString(), row);
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(tail);
            } catch (NumberFormatException ex) {
                problems.add("enchanted-books.overrides 中的等级无效：" + key);
                continue;
            }
            if (level < 1 || level > ench.getMaxLevel()) {
                problems.add("enchanted-books.overrides 中 " + key + " 的等级超出 1.."
                        + ench.getMaxLevel() + "，已忽略");
                continue;
            }
            out.put(ench.getKey() + "/" + level, row);
        }
        return out;
    }

    private static Set<Enchantment> resolveAll(List<String> names, List<String> problems, String where) {
        if (names.isEmpty()) {
            return Set.of();
        }
        Set<Enchantment> out = new HashSet<>(names.size() * 2);
        for (String name : names) {
            Enchantment ench = resolve(name);
            if (ench == null) {
                problems.add("enchanted-books." + where + " 中的附魔无法识别：" + name);
            } else {
                out.add(ench);
            }
        }
        return out;
    }

    /** Accepts {@code MENDING}, {@code mending} or {@code minecraft:mending}. */
    private static Enchantment resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String raw = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = raw.indexOf(':') < 0
                ? NamespacedKey.minecraft(raw)
                : NamespacedKey.fromString(raw);
        return key == null ? null : registry().get(key);
    }

    /**
     * The live enchantment registry. Read through {@link RegistryAccess} rather
     * than the deprecated {@code Registry.ENCHANTMENT} constant: enchantments are
     * data-driven, so the registry is per-server and only valid after datapacks
     * have loaded.
     */
    private static Registry<Enchantment> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    }
}
