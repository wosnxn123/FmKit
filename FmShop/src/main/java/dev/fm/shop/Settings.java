package dev.fm.shop;

import dev.fm.shop.util.Money;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/** Typed access to config.yml. Reload via load() after plugin.reloadConfig(). */
public final class Settings {

    /** Where transaction fees go once collected. */
    public enum FeeSink { VOID, TAX_POOL }

    private final FmShopPlugin plugin;
    private FileConfiguration cfg;

    public Settings(FmShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        cfg = plugin.getConfig();
    }

    // ---- currency ----
    public String currency() {
        return cfg.getString("currency.name", "金币");
    }

    /** Balance handed to a player the first time their file is created. */
    public long startingBalance() {
        return clampCents(cfg.getDouble("currency.starting-balance", 100.0));
    }

    // ---- fees ----
    /** Fee taken out of a sale payout, in basis points (250 = 2.5%). */
    public int sellFeeBp() {
        return clampBp(cfg.getInt("fees.sell-bp", 500));
    }

    /** Surcharge added on top of a purchase, in basis points. */
    public int buyFeeBp() {
        return clampBp(cfg.getInt("fees.buy-bp", 0));
    }

    /** Fee taken out of a player-to-player transfer, in basis points. */
    public int payFeeBp() {
        return clampBp(cfg.getInt("fees.pay-bp", 200));
    }

    public FeeSink feeSink() {
        return "tax-pool".equalsIgnoreCase(cfg.getString("fees.destination", "void"))
                ? FeeSink.TAX_POOL : FeeSink.VOID;
    }

    // ---- limits ----
    /** Hard cap on items moved by a single buy or sell action. */
    public int maxPerAction() {
        return Math.max(1, Math.min(4096, cfg.getInt("limits.max-per-action", 2304)));
    }

    /** Smallest transfer accepted by /fmshop pay, in cents. */
    public long minPay() {
        return Math.max(1, clampCents(cfg.getDouble("limits.min-pay", 1.0)));
    }

    /** Local hour at which per-player daily quotas roll over. */
    public int resetHour() {
        return Math.max(0, Math.min(23, cfg.getInt("limits.reset-hour", 4)));
    }

    /** Quota day number, shifted so the day flips at {@link #resetHour()}. */
    public long today() {
        return LocalDateTime.now(ZoneId.systemDefault())
                .minusHours(resetHour())
                .toLocalDate()
                .toEpochDay();
    }

    /** Milliseconds until the next quota rollover, for GUI countdowns. */
    public long untilReset() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime next = now.toLocalDate().atStartOfDay().plusHours(resetHour());
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return java.time.Duration.between(now, next).toMillis();
    }

    // ---- gui ----
    public int guiAutoRefreshSeconds() {
        return Math.max(0, cfg.getInt("gui.auto-refresh-seconds", 5));
    }

    public boolean sounds() {
        return cfg.getBoolean("gui.sounds", true);
    }

    public Material icon(String key, Material def) {
        Material m = Material.matchMaterial(cfg.getString("gui.icons." + key, def.name()));
        return m != null && m.isItem() ? m : def;
    }

    // ---- audit ----
    public boolean auditEnabled() {
        return cfg.getBoolean("audit.enabled", true);
    }

    public int auditKeepDays() {
        return Math.max(1, cfg.getInt("audit.keep-days", 14));
    }

    /** Transactions at or above this value are broadcast to admins. */
    public long auditAlertAbove() {
        return clampCents(cfg.getDouble("audit.alert-above", 10000.0));
    }

    // ---- doctor ----
    /**
     * When true, a price row that fails an arbitrage check is dropped instead of
     * merely warned about. Off by default: a fresh operator editing prices
     * should be told, not silently have items vanish from the shop.
     */
    public boolean doctorStrict() {
        return cfg.getBoolean("doctor.strict", false);
    }

    // ---- messages ----
    public String msg(String key) {
        return cfg.getString("messages." + key, FALLBACK_MESSAGES.getOrDefault(key, ""));
    }

    /** Message with prefix prepended. */
    public String prefixed(String key) {
        return msg("prefix") + msg(key);
    }

    private long clampCents(double v) {
        long c = Money.ofDouble(v);
        return c < 0 ? 0 : c;
    }

    private static int clampBp(int bp) {
        return Math.max(0, Math.min(9_000, bp));
    }

    /** Defaults for message keys added after initial release; deployed configs lack them. */
    private static final Map<String, String> FALLBACK_MESSAGES = Map.ofEntries(
            Map.entry("prefix", "<gray>[<gold>商店</gold>]</gray> "),
            Map.entry("no-permission", "<red>你没有权限</red>"),
            Map.entry("players-only", "<red>该指令只能由玩家使用</red>"),
            Map.entry("balance", "<green>余额：<white>{amount}</white></green>"),
            Map.entry("balance-other", "<green><white>{player}</white> 的余额：<white>{amount}</white></green>"),
            Map.entry("unknown-item", "<red>未找到该商品：<white>{input}</white></red>"),
            Map.entry("not-buyable", "<red>{item} 不出售</red>"),
            Map.entry("not-sellable", "<red>{item} 不回收</red>"),
            Map.entry("price-line", "<gray>{item}</gray> <dark_gray>|</dark_gray> <green>买入 <white>{buy}</white></green> <dark_gray>|</dark_gray> <gold>卖出 <white>{sell}</white></gold>"),
            Map.entry("buy-ok", "<green>购买 {item} ×<white>{n}</white>，支付 <white>{cost}</white></green>"),
            Map.entry("sell-ok", "<gold>出售 {item} ×<white>{n}</white>，获得 <white>{gain}</white></gold>"),
            Map.entry("sell-fee", "<gray>（手续费 <white>{fee}</white>）</gray>"),
            Map.entry("no-money", "<red>余额不足，还需 <white>{need}</white></red>"),
            Map.entry("no-space", "<red>背包空间不足</red>"),
            Map.entry("nothing-to-sell", "<red>背包里没有可回收的物品</red>"),
            Map.entry("no-sell-creative", "<red>创造模式无法出售物品</red>"),
            Map.entry("sell-all-ok", "<gold>回收 <white>{kinds}</white> 种共 <white>{n}</white> 件，获得 <white>{gain}</white></gold>"),
            Map.entry("hand-empty", "<red>手上没有物品</red>"),
            Map.entry("price-dynamic", "<gray>行情 <white>{percent}%</white>（{trend}）</gray>"),
            Map.entry("admin-market-reset", "<green>已重置 <white>{item}</white> 的行情</green>"),
            Map.entry("quota-buy", "<red>{item} 今日限购 <white>{limit}</white>，剩余 <white>{left}</white>（<white>{reset}</white> 后重置）</red>"),
            Map.entry("quota-sell", "<red>{item} 今日限售 <white>{limit}</white>，剩余 <white>{left}</white>（<white>{reset}</white> 后重置）</red>"),
            Map.entry("pay-ok", "<green>已向 <white>{player}</white> 转账 <white>{amount}</white></green>"),
            Map.entry("pay-recv", "<green>收到 <white>{player}</white> 的转账 <white>{amount}</white></green>"),
            Map.entry("pay-self", "<red>不能给自己转账</red>"),
            Map.entry("pay-too-small", "<red>最少转账 <white>{min}</white></red>"),
            Map.entry("pay-failed", "<red>转账失败：余额不足或对方账户已满</red>"),
            Map.entry("player-not-found", "<red>找不到玩家 <white>{player}</white></red>"),
            Map.entry("bad-amount", "<red>金额无效：<white>{input}</white></red>"),
            Map.entry("admin-give", "<green>已给 <white>{player}</white> 增加 <white>{amount}</white></green>"),
            Map.entry("admin-take", "<green>已从 <white>{player}</white> 扣除 <white>{amount}</white></green>"),
            Map.entry("admin-set", "<green>已将 <white>{player}</white> 的余额设为 <white>{amount}</white></green>"),
            Map.entry("admin-price", "<green>已设置 {item} 价格：买入 <white>{buy}</white>，卖出 <white>{sell}</white></green>"),
            Map.entry("admin-reset-limit", "<green>已重置 <white>{player}</white> 的今日限额</green>"),
            Map.entry("reloaded", "<green>配置已重载：<white>{items}</white> 件商品，<white>{cats}</white> 个分类</green>"),
            Map.entry("gui-title-hub", "<dark_gray>商店</dark_gray>"),
            Map.entry("gui-title-buy", "<dark_gray>购买 · {category}</dark_gray>"),
            Map.entry("gui-title-sell", "<dark_gray>回收 · {category}</dark_gray>"),
            Map.entry("gui-title-bag", "<dark_gray>一键回收</dark_gray>"),
            Map.entry("gui-title-confirm", "<dark_gray>确认 · {item}</dark_gray>"));
}
