package dev.fm.kit.papi;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.bin.BinEntry;
import dev.fm.kit.bin.PrivateBin;
import dev.fm.kit.util.TimeUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI 扩展（%fmkit_*%）。
 *
 * 异步安全：TAB 等插件在异步线程请求占位符。这里只读三类状态——
 * volatile 字段（SweepScheduler 的扫地时钟/上轮统计、本类的配置快照）、
 * ConcurrentHashMap（PrivateBinStore.bins）、以及 store 自带 synchronized 的计数/快照方法；
 * 绝不触碰 world/entity/inventory API，不做 IO，不阻塞。
 * 配置衍生值（容量上限、清扫间隔、回收默认开关）由主线程在启用与 reload 时写入
 * 本类的 volatile 快照，异步线程只读快照，不直接读 FileConfiguration。
 */
public final class FmKitPlaceholders extends PlaceholderExpansion {

    private final FmKitPlugin plugin;

    // 配置快照：主线程在 refreshConfigCache() 写入，异步线程只读。
    private volatile int privateMax;
    private volatile int publicMax;
    private volatile int sweepInterval;
    private volatile boolean collectDefault;

    public FmKitPlaceholders(FmKitPlugin plugin) {
        this.plugin = plugin;
        refreshConfigCache();
    }

    /** 主线程调用：onEnable 注册后、/fmkitadmin reload 后刷新配置快照。 */
    public void refreshConfigCache() {
        var s = plugin.settings();
        privateMax = s.privateMaxEntries();
        publicMax = s.publicMaxEntries();
        sweepInterval = s.cleanInterval();
        collectDefault = s.collectDefault();
    }

    @Override
    public @NotNull String getIdentifier() {
        return "fmkit";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Fm";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            return switch (params.toLowerCase(Locale.ROOT)) {
                // ---- 服务器作用域 ----
                case "public_entries" -> String.valueOf(plugin.publicStore().size());
                case "public_max" -> publicMax <= 0 ? "不限" : String.valueOf(publicMax);
                case "sweep_countdown" -> sweepCountdown(false);
                case "sweep_countdown_formatted" -> sweepCountdown(true);
                case "sweep_enabled" -> plugin.sweep().isRunning() ? "开启" : "关闭";
                case "sweep_interval" -> String.valueOf(sweepInterval);
                case "last_sweep_entries" -> lastSweep(true);
                case "last_sweep_items" -> lastSweep(false);
                // ---- 玩家作用域（无玩家上下文时返回 null） ----
                case "private_entries" -> player == null ? null
                        : String.valueOf(plugin.privateStore().size(player.getUniqueId()));
                case "private_max" -> player == null ? null
                        : (privateMax <= 0 ? "不限" : String.valueOf(privateMax));
                case "private_collect" -> player == null ? null : collectState(player.getUniqueId());
                case "private_next_expiry" -> player == null ? null : nextExpiry(player.getUniqueId());
                default -> null;
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** 玩家回收开关：未建档的玩家回落到配置默认值（读快照，不碰 FileConfiguration）。 */
    private String collectState(UUID uuid) {
        PrivateBin bin = plugin.privateStore().bins().get(uuid);
        boolean on = bin != null ? bin.collectEnabled() : collectDefault;
        return on ? "开启" : "关闭";
    }

    /** 私人箱最早一条的剩余保留时间（TimeUtil 中文格式），空箱返回"无"。 */
    private String nextExpiry(UUID uuid) {
        List<BinEntry> entries = plugin.privateStore().snapshot(uuid);
        if (entries.isEmpty()) {
            return "无";
        }
        long min = Long.MAX_VALUE;
        for (BinEntry e : entries) {
            min = Math.min(min, e.expireAt());
        }
        long remaining = min - System.currentTimeMillis();
        return remaining <= 0 ? "即将过期" : TimeUtil.format(remaining);
    }

    /** 距下次清扫的秒数；formatted 变体返回 m:ss。清扫关闭时返回 "-"。 */
    private String sweepCountdown(boolean formatted) {
        if (!plugin.sweep().isRunning()) {
            return "-";
        }
        long sec = Math.max(0L, (plugin.sweep().nextCleanAt() - System.currentTimeMillis()) / 1000L);
        if (!formatted) {
            return String.valueOf(sec);
        }
        long m = sec / 60;
        long s = sec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    /** 上轮清扫统计；-1（尚未清扫过）显示为 "-"。 */
    private String lastSweep(boolean entries) {
        int v = entries ? plugin.sweep().lastSweepEntries() : plugin.sweep().lastSweepItems();
        return v < 0 ? "-" : String.valueOf(v);
    }
}
