package dev.fm.shop.audit;

import dev.fm.shop.FmShopPlugin;
import dev.fm.shop.util.ItemNames;
import dev.fm.shop.util.Money;
import dev.fm.shop.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Append-only transaction log under plugins/FmShop/audit/&lt;date&gt;.log.
 *
 * <p>Line format is fixed-field and tab-free so it stays greppable:
 * {@code 12:04:31 BUY Steve iron_ingot x64 gross=384.00 fee=0.00 net=384.00 bal=1216.00}
 *
 * <p>Writes are queued to a single daemon thread; the admin alert for large
 * trades is dispatched on the calling thread, where the player list is already
 * safe to touch, before the line is handed off.
 */
public final class AuditLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final FmShopPlugin plugin;
    private final Path dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FmShop-Audit");
        t.setDaemon(true);
        return t;
    });

    public AuditLog(FmShopPlugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("audit");
    }

    /**
     * @param kind  BUY / SELL / PAY / ADMIN
     * @param mat   traded material, null for money-only events
     * @param gross value before fees, in cents
     * @param net   balance delta actually applied, in cents
     */
    public void log(Player who, String kind, Material mat, int qty,
                    long gross, long fee, long net, long balanceAfter) {
        if (!plugin.settings().auditEnabled()) {
            return;
        }
        String line = LocalDateTime.now().format(TIME)
                + " " + kind
                + " " + who.getName()
                + " " + (mat == null ? "-" : ItemNames.plain(mat).replace(' ', '_'))
                + " x" + qty
                + " gross=" + Money.format(gross)
                + " fee=" + Money.format(fee)
                + " net=" + Money.format(net)
                + " bal=" + Money.format(balanceAfter);
        alert(who, kind, mat, qty, net);
        append(line);
    }

    /** Console/admin-initiated money movement, recorded under the target player. */
    public void logAdmin(String actor, String targetName, String what, long amount, long balanceAfter) {
        if (!plugin.settings().auditEnabled()) {
            return;
        }
        append(LocalDateTime.now().format(TIME)
                + " ADMIN " + targetName
                + " " + what
                + " x1 gross=" + Money.format(amount)
                + " fee=" + Money.format(0)
                + " net=" + Money.format(amount)
                + " bal=" + Money.format(balanceAfter)
                + " by=" + actor);
    }

    private void alert(Player who, String kind, Material mat, int qty, long net) {
        long threshold = plugin.settings().auditAlertAbove();
        if (threshold <= 0 || Math.abs(net) < threshold) {
            return;
        }
        String item = mat == null ? "" : " " + ItemNames.mini(mat) + " ×" + qty;
        String msg = "<gray>[<gold>审计</gold>]</gray> <white>" + who.getName() + "</white> "
                + kind + item + " <yellow>" + Money.format(net, plugin.settings().currency()) + "</yellow>";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("fmshop.admin")) {
                TextUtil.msg(p, msg);
            }
        }
        Bukkit.getConsoleSender().sendMessage(TextUtil.mini(msg));
    }

    private void append(String line) {
        io.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(LocalDate.now().format(DATE) + ".log"),
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                plugin.getLogger().warning("审计日志写入失败：" + ex.getMessage());
            }
        });
    }

    /**
     * Last {@code limit} lines mentioning {@code playerName}, newest first,
     * scanning today backwards. Runs on the audit thread; the callback is
     * resumed on the global region scheduler.
     */
    public void tail(String playerName, int limit, Consumer<List<String>> done) {
        String needle = " " + playerName + " ";
        io.execute(() -> {
            List<String> hits = new ArrayList<>();
            LocalDate day = LocalDate.now();
            for (int back = 0; back < plugin.settings().auditKeepDays() && hits.size() < limit; back++) {
                Path f = dir.resolve(day.minusDays(back).format(DATE) + ".log");
                if (!Files.exists(f)) {
                    continue;
                }
                try {
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    Collections.reverse(lines);
                    String prefix = day.minusDays(back).format(DATE) + " ";
                    for (String l : lines) {
                        if (playerName == null || l.contains(needle)) {
                            hits.add(prefix + l);
                            if (hits.size() >= limit) {
                                break;
                            }
                        }
                    }
                } catch (IOException ex) {
                    plugin.getLogger().warning("审计日志读取失败：" + ex.getMessage());
                }
            }
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> done.accept(hits));
        });
    }

    /** Drops logs older than the retention window. */
    public void prune() {
        io.execute(() -> {
            if (!Files.isDirectory(dir)) {
                return;
            }
            LocalDate cutoff = LocalDate.now().minusDays(plugin.settings().auditKeepDays());
            try (var files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(p -> {
                    String name = p.getFileName().toString();
                    String stamp = name.substring(0, name.length() - 4);
                    try {
                        if (LocalDate.parse(stamp, DATE).isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (RuntimeException | IOException ignored) {
                        // Not one of ours, or already gone; leave it alone.
                    }
                });
            } catch (IOException ex) {
                plugin.getLogger().warning("审计日志清理失败：" + ex.getMessage());
            }
        });
    }

    /** Log file names currently on disk, for /fsa status. */
    public int fileCount() {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return (int) files.filter(p -> p.getFileName().toString()
                    .toLowerCase(Locale.ROOT).endsWith(".log")).count();
        } catch (IOException ex) {
            return 0;
        }
    }

    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
