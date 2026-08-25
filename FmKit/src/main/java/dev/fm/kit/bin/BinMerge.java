package dev.fm.kit.bin;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * Stack-merge on deposit: an incoming stack is absorbed into existing entries
 * with an identical item (type + meta) and compatible owner. Absorbed groups
 * get their expiry renewed to the newest deposit (deposit time keeps the first
 * deposit); overflow beyond a group's max stack size opens a new entry. Groups
 * capped at max stack size stop receiving, so renewal stops with them.
 */
public final class BinMerge {

    private BinMerge() {
    }

    /**
     * @return true if at least one new entry was appended (caller applies capacity rules)
     */
    public static boolean merge(List<BinEntry> entries, ItemStack item, String ownerName,
                                long depositAt, long expireAt, Predicate<BinEntry> compatible) {
        ItemStack remaining = item.clone();
        for (BinEntry e : entries) {
            if (remaining.getAmount() <= 0) {
                break;
            }
            ItemStack group = e.item();
            if (compatible.test(e) && group.isSimilar(remaining) && group.getAmount() < group.getMaxStackSize()) {
                int add = Math.min(group.getMaxStackSize() - group.getAmount(), remaining.getAmount());
                group.setAmount(group.getAmount() + add);
                remaining.setAmount(remaining.getAmount() - add);
                e.renewExpiry(expireAt);
            }
        }
        if (remaining.getAmount() <= 0) {
            return false;
        }
        while (remaining.getAmount() > 0) {
            int take = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
            ItemStack part = remaining.clone();
            part.setAmount(take);
            entries.add(new BinEntry(BinEntry.newId(), part, ownerName, depositAt, expireAt));
            remaining.setAmount(remaining.getAmount() - take);
        }
        return true;
    }
}
