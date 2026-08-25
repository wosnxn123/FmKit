package dev.fm.kit.bin;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One stored item stack in a recycle bin. */
public final class BinEntry {

    private final String id;
    private final ItemStack item;
    private final String ownerName;
    private long depositAt;
    private long expireAt;
    /** Arrival sequence within one sweep round: negated entity age (ticks lived), so
        smaller = older = evicted first. In-memory only, never persisted. */
    private long seq;

    public BinEntry(String id, ItemStack item, String ownerName, long depositAt, long expireAt) {
        this(id, item, ownerName, depositAt, expireAt, 0L);
    }

    public BinEntry(String id, ItemStack item, String ownerName, long depositAt, long expireAt, long seq) {
        this.id = id;
        this.item = item;
        this.ownerName = ownerName;
        this.depositAt = depositAt;
        this.expireAt = expireAt;
        this.seq = seq;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public String id() {
        return id;
    }

    public ItemStack item() {
        return item;
    }

    public String ownerName() {
        return ownerName;
    }

    public long depositAt() {
        return depositAt;
    }

    public long expireAt() {
        return expireAt;
    }

    public long seq() {
        return seq;
    }

    /** Entry for the public bin after private expiry: the original deposit
     *  time is kept (deposit time never renews); only the expiry restarts. */
    public BinEntry renewedForPublic(long publicTtlMs) {
        long now = System.currentTimeMillis();
        return new BinEntry(id, item, ownerName, depositAt, now + publicTtlMs, seq);
    }

    /** Merge-renewal: same id/item, expiry pushed to the newest deposit;
     *  deposit time keeps the group's first deposit. */
    public void renewExpiry(long expireAt) {
        this.expireAt = expireAt;
    }
}
