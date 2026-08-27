package dev.fm.shop.store;

import dev.fm.shop.util.ItemNames;
import dev.fm.shop.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Arbitrage checker for the price table, run at load and by {@code /fsa doctor}.
 *
 * <p>A shop that both buys and sells is a money printer the moment any path
 * turns cheap items into expensive ones. The dangerous paths are not guesswork:
 * they are the server's own recipe registry. For every sellable item this walks
 * the real recipes that produce it, prices the cheapest legal ingredient set at
 * the shop's BUY prices, and compares that against what the shop pays for the
 * output. Anything with a positive margin is a loop a player can run forever.
 *
 * <p>Ingredients the shop does not sell are reported separately: the shop can't
 * hand them out, but a farm might, so those are warnings for the operator to
 * judge rather than hard errors.
 *
 * <p>Prices are checked at base (100%) multipliers on purpose. Dynamic pricing
 * scales an item's buy and sell side by the same factor, so a table that is
 * loop-free at 100% stays loop-free at every multiplier.
 */
public final class PriceDoctor {

    public enum Severity { ERROR, WARN, INFO }
    /** {@code subject} is the priced item at fault, or null for table-level notes. */
    public record Finding(Severity severity, Material subject, String text) {
    }

    private PriceDoctor() {
    }

    public static List<Finding> run(PriceCatalog cat) {
        List<Finding> out = new ArrayList<>();
        for (String key : cat.unknown()) {
            out.add(new Finding(Severity.WARN, null, "未知物品 ID：" + key + "（该版本没有此材质，已忽略）"));
        }
        for (String p : cat.problems()) {
            out.add(new Finding(Severity.WARN, null, p));
        }
        Set<String> declared = new HashSet<>();
        for (Category c : cat.categories()) {
            declared.add(c.id());
        }
        for (PriceEntry e : cat.all()) {
            if (!declared.contains(e.category())) {
                out.add(new Finding(Severity.WARN, null,
                        ItemNames.plain(e.material()) + " 的分类 " + e.category() + " 未定义，界面中不可见"));
            }
            if (e.buyable() && e.sellable() && e.sell() >= e.buy()) {
                out.add(new Finding(Severity.ERROR, e.material(),
                        ItemNames.plain(e.material()) + " 买卖价倒挂：买入 "
                                + Money.format(e.buy()) + " ≤ 卖出 " + Money.format(e.sell())
                                + "，可无限刷钱"));
            }
            if (e.sellable()) {
                checkRecipes(cat, e, out);
            }
        }
        return out;
    }

    /**
     * Strict mode: drops every item an ERROR finding blames, so a mispriced row
     * cannot be traded until the operator fixes it. Returns how many were pulled.
     */
    public static int enforce(PriceCatalog cat, List<Finding> findings) {
        int pulled = 0;
        for (Finding f : findings) {
            if (f.severity() == Severity.ERROR && f.subject() != null && cat.remove(f.subject())) {
                pulled++;
            }
        }
        return pulled;
    }

    /** Highest severity present, or null when the table is clean. */
    public static Severity worst(List<Finding> findings) {
        Severity worst = null;
        for (Finding f : findings) {
            if (worst == null || f.severity().ordinal() < worst.ordinal()) {
                worst = f.severity();
            }
        }
        return worst;
    }

    private static void checkRecipes(PriceCatalog cat, PriceEntry product, List<Finding> out) {
        List<Recipe> recipes;
        try {
            recipes = Bukkit.getRecipesFor(new ItemStack(product.material()));
        } catch (RuntimeException ex) {
            return;
        }
        for (Recipe r : recipes) {
            List<RecipeChoice> inputs = inputsOf(r);
            if (inputs.isEmpty()) {
                continue;
            }
            long cost = 0;
            int unpriced = 0;
            for (RecipeChoice c : inputs) {
                long price = cheapestBuy(cat, c);
                if (price < 0) {
                    unpriced++;
                } else {
                    cost = Money.add(cost, price);
                }
            }
            int made = Math.max(1, r.getResult().getAmount());
            long revenue = Money.times(product.sell(), made);
            if (revenue <= cost) {
                continue;
            }
            String desc = ItemNames.plain(product.material()) + " ×" + made
                    + " 卖出 " + Money.format(revenue)
                    + " > 材料买入 " + Money.format(cost)
                    + "（配方 " + describe(r) + "）";
            out.add(unpriced == 0
                    ? new Finding(Severity.ERROR, product.material(), "合成套利：" + desc)
                    : new Finding(Severity.WARN, product.material(),
                            "疑似合成套利（" + unpriced + " 种材料商店不出售）：" + desc));
        }
    }

    private static List<RecipeChoice> inputsOf(Recipe r) {
        List<RecipeChoice> list = new ArrayList<>(9);
        if (r instanceof ShapedRecipe shaped) {
            for (RecipeChoice c : shaped.getChoiceMap().values()) {
                if (c != null) {
                    list.add(c);
                }
            }
        } else if (r instanceof ShapelessRecipe shapeless) {
            list.addAll(shapeless.getChoiceList());
        } else if (r instanceof CookingRecipe<?> cooking) {
            list.add(cooking.getInputChoice());
        } else if (r instanceof StonecuttingRecipe cutting) {
            list.add(cutting.getInputChoice());
        }
        // Smithing and crafting-transmute recipes consume gear the shop never
        // prices, so there is no purchasable input set to arbitrage.
        return list;
    }

    /**
     * Cheapest shop buy price among the materials this slot accepts, or -1 when
     * the shop sells none of them. A player picking the cheapest legal option is
     * exactly the worst case a price table has to survive.
     */
    private static long cheapestBuy(PriceCatalog cat, RecipeChoice choice) {
        long best = -1;
        if (choice instanceof RecipeChoice.MaterialChoice mc) {
            for (Material m : mc.getChoices()) {
                best = better(best, cat.get(m));
            }
        } else if (choice instanceof RecipeChoice.ExactChoice ec) {
            for (ItemStack it : ec.getChoices()) {
                // An exact-NBT slot cannot be filled with a plain shop item.
                if (it != null && !it.hasItemMeta()) {
                    best = better(best, cat.get(it.getType()));
                }
            }
        }
        return best;
    }

    private static long better(long best, PriceEntry e) {
        if (e == null || !e.buyable()) {
            return best;
        }
        return best < 0 ? e.buy() : Math.min(best, e.buy());
    }

    private static String describe(Recipe r) {
        if (r instanceof ShapedRecipe) {
            return "有序合成";
        }
        if (r instanceof ShapelessRecipe) {
            return "无序合成";
        }
        if (r instanceof CookingRecipe<?>) {
            return "熔炼";
        }
        if (r instanceof StonecuttingRecipe) {
            return "切石";
        }
        return r.getClass().getSimpleName();
    }
}
