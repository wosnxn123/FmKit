package dev.fm.kit.bin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One player's private bin. All list access must be synchronized on this object. */
public final class PrivateBin {

    private final UUID owner;
    private final List<BinEntry> entries = new ArrayList<>();
    private boolean collectEnabled;
    /** Expiry notify mode (off / valuable only / all). */
    private NotifyMode notifyMode;
    /** Expiry destination: true = destroy, false = move to the public bin. */
    private boolean expiryDestroy;

    public PrivateBin(UUID owner, boolean collectEnabled, NotifyMode notifyMode, boolean expiryDestroy) {
        this.owner = owner;
        this.collectEnabled = collectEnabled;
        this.notifyMode = notifyMode;
        this.expiryDestroy = expiryDestroy;
    }

    public UUID owner() {
        return owner;
    }

    public List<BinEntry> entries() {
        return entries;
    }

    public boolean collectEnabled() {
        return collectEnabled;
    }

    public void setCollectEnabled(boolean collectEnabled) {
        this.collectEnabled = collectEnabled;
    }

    public NotifyMode notifyMode() {
        return notifyMode;
    }

    public void setNotifyMode(NotifyMode notifyMode) {
        this.notifyMode = notifyMode;
    }

    public boolean expiryDestroy() {
        return expiryDestroy;
    }

    public void setExpiryDestroy(boolean expiryDestroy) {
        this.expiryDestroy = expiryDestroy;
    }
}
