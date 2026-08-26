package dev.fm.kit.bin;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.util.ItemNames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Console audit log for bin flows, gated by the bins.log.* modes:
 * OFF silent; EACH one line per event; WINDOW aggregates into windows flushed at
 * scan/round end; EPISODE (overflow keys) one line per continuous overflow episode,
 * ended when the store drops back below capacity. For expire/sweep keys EPISODE
 * behaves like WINDOW. Aggregation state is guarded by this monitor.
 */
public final class BinLogger {

    public enum Mode {
        OFF, EACH, WINDOW, EPISODE;

        public static Mode parse(String s, Mode def) {
            if (s == null || s.isBlank()) {
                return def;
            }
            try {
                return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return def;
            }
        }
    }

    private final FmKitPlugin plugin;
    private final Mode overflow;
    private final Mode publicExpire;
    private final Mode privateExpire;
    private final Mode sweepDeposit;

    /** Continuous overflow episodes in flight; ended by *BelowCap. Guarded by this. */
    private final Set<UUID> privateOverflowEpisode = new HashSet<>();
    private boolean publicOverflowEpisode;

    /** Window aggregation, guarded by this. */
    private final Map<UUID, Integer> privateOverflowWindow = new HashMap<>();
    private int publicOverflowWindow;
    private final Map<UUID, Integer> privateExpireWindow = new HashMap<>(); // moved count only; destroyed logged unconditionally
    private final List<BinEntry> publicExpireWindow = new ArrayList<>();
    private int sweepCollected;
    private int sweepAmount;
    private int sweepPublic;

    public BinLogger(FmKitPlugin plugin) {
        this.plugin = plugin;
        var s = plugin.settings();
        this.overflow = s.logOverflowMode();
        this.publicExpire = s.logPublicExpireMode();
        this.privateExpire = s.logPrivateExpireMode();
        this.sweepDeposit = s.logSweepDepositMode();
    }

    // ---- capacity overflow ----

    public void privateOverflow(UUID uuid, BinEntry dropped, int size, int max) {
        switch (overflow) {
            case OFF -> {}
            case EPISODE -> {
                synchronized (this) {
                    if (privateOverflowEpisode.add(uuid)) {
                        info("私人箱满包：" + name(uuid) + " 超过上限 " + max + "，最旧 "
                                + describe(dropped) + " 转入公共回收站（溢出期间不再重复提示）");
                    }
                }
            }
            case EACH -> info("私人箱满包：" + name(uuid) + " 超过上限 " + max + "，最旧 "
                    + describe(dropped) + " 转入公共回收站");
            case WINDOW -> {
                synchronized (this) {
                    privateOverflowWindow.merge(uuid, 1, Integer::sum);
                }
            }
        }
    }

    public void privateBelowCap(UUID uuid) {
        if (overflow != Mode.EPISODE) {
            return;
        }
        synchronized (this) {
            if (privateOverflowEpisode.remove(uuid)) {
                info("私人箱恢复：" + name(uuid) + " 已低于容量上限，溢出结束");
            }
        }
    }

    public void publicOverflow(BinEntry dropped, int max) {
        switch (overflow) {
            case OFF -> {}
            case EPISODE -> {
                synchronized (this) {
                    if (!publicOverflowEpisode) {
                        publicOverflowEpisode = true;
                        info("公共回收站满包：超过上限 " + max + "，最旧 " + describe(dropped)
                                + " 被丢弃（溢出期间不再重复提示）");
                    }
                }
            }
            case EACH -> info("公共回收站满包：超过上限 " + max + "，最旧 " + describe(dropped) + " 被丢弃");
            case WINDOW -> {
                synchronized (this) {
                    publicOverflowWindow++;
                }
            }
        }
    }

    public void publicBelowCap() {
        if (overflow != Mode.EPISODE) {
            return;
        }
        synchronized (this) {
            if (publicOverflowEpisode) {
                publicOverflowEpisode = false;
                info("公共回收站恢复：已低于容量上限，溢出结束");
            }
        }
    }

    // ---- expiry ----

    public void publicExpire(List<BinEntry> purged) {
        if (purged.isEmpty() || publicExpire == Mode.OFF) {
            return;
        }
        if (publicExpire == Mode.EACH) {
            info("公共回收站到期清理 " + purged.size() + " 条：" + BinExpiryTask.summarize(purged, 3));
            return;
        }
        synchronized (this) {
            publicExpireWindow.addAll(purged);
        }
    }

    public void privateExpire(UUID uuid, List<BinEntry> moved, List<BinEntry> destroyed) {
        if (!destroyed.isEmpty()) {
            info("私人箱到期销毁：" + name(uuid) + " 销毁 " + destroyed.size() + " 条：" + BinExpiryTask.summarize(destroyed, 3));
        }
        if (moved.isEmpty() || privateExpire == Mode.OFF) {
            return;
        }
        if (privateExpire == Mode.EACH) {
            info("私人箱到期转公共：" + name(uuid) + " 转公共 " + moved.size() + " 条：" + BinExpiryTask.summarize(moved, 3));
            return;
        }
        synchronized (this) {
            privateExpireWindow.merge(uuid, moved.size(), Integer::sum);
        }
    }

    // ---- sweep ----

    public void sweepRound(int collected, int amount, int publicCount) {
        if (collected == 0 || sweepDeposit == Mode.OFF) {
            return;
        }
        if (sweepDeposit == Mode.EACH) {
            info("扫地入账：收走 " + collected + " 项/" + amount + " 件，其中 " + publicCount + " 项进公共回收站");
            return;
        }
        synchronized (this) {
            sweepCollected += collected;
            sweepAmount += amount;
            sweepPublic += publicCount;
        }
    }

    /** Called at the end of every expiry scan: emit whatever the windows collected. */
    public void flushWindows() {
        synchronized (this) {
            if (!privateOverflowWindow.isEmpty()) {
                StringBuilder sb = new StringBuilder("私人箱满包（窗口汇总）：");
                privateOverflowWindow.forEach((uuid, n) -> sb.append(name(uuid)).append('×').append(n).append(' '));
                info(sb.toString().trim());
                privateOverflowWindow.clear();
            }
            if (publicOverflowWindow > 0) {
                info("公共回收站满包（窗口汇总）：丢弃 " + publicOverflowWindow + " 条最旧条目");
                publicOverflowWindow = 0;
            }
            if (!privateExpireWindow.isEmpty()) {
                StringBuilder sb = new StringBuilder("私人箱到期转公共（窗口汇总）：");
                privateExpireWindow.forEach((uuid, n) -> sb.append(name(uuid)).append('×').append(n).append("条 "));
                info(sb.toString().trim());
                privateExpireWindow.clear();
            }
            if (!publicExpireWindow.isEmpty()) {
                info("公共回收站到期清理（窗口汇总） " + publicExpireWindow.size() + " 条："
                        + BinExpiryTask.summarize(publicExpireWindow, 3));
                publicExpireWindow.clear();
            }
            if (sweepCollected > 0) {
                info("扫地入账（窗口汇总）：收走 " + sweepCollected + " 项/" + sweepAmount
                        + " 件，其中 " + sweepPublic + " 项进公共回收站");
                sweepCollected = 0;
                sweepAmount = 0;
                sweepPublic = 0;
            }
        }
    }


    private String name(UUID uuid) {
        String n = plugin.privateStore().knownName(uuid);
        return n != null ? n : uuid.toString().substring(0, 8);
    }

    private static String describe(BinEntry e) {
        return ItemNames.describe(e.item()) + " ×" + e.item().getAmount();
    }

    private void info(String text) {
        plugin.getLogger().info("[bin] " + text);
    }
}
