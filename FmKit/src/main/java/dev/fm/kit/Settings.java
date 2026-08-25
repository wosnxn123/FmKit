package dev.fm.kit;

import dev.fm.kit.util.TimeUtil;
import dev.fm.kit.bin.NotifyMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed access to config.yml. Reload via load() after plugin.reloadConfig(). */
public final class Settings {

    private final FmKitPlugin plugin;
    private FileConfiguration cfg;

    public Settings(FmKitPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        cfg = plugin.getConfig();
        migrateLegacyWhitelist();
    }

    /**
     * One-time migration of the old unified whitelist (sweep.whitelisted-items
     * + whitelist-mode) into the split valuable/ignore lists. EXEMPT mode keeps
     * sweep exemption enabled; VALUABLE mode leaves the ignore switch off.
     */
    private void migrateLegacyWhitelist() {
        if (cfg.get("sweep.whitelisted-items") == null) {
            return;
        }
        List<String> old = cfg.getStringList("sweep.whitelisted-items");
        String mode = cfg.getString("sweep.whitelist-mode");
        boolean exempt = mode != null
                ? "EXEMPT".equalsIgnoreCase(mode)
                : cfg.getBoolean("sweep.whitelist-enabled", false);
        cfg.set("sweep.valuable-enabled", !exempt);
        cfg.set("sweep.valuable-items", old);
        cfg.set("sweep.ignore.enabled", exempt);
        cfg.set("sweep.ignore.items", old);
        cfg.set("sweep.whitelisted-items", null);
        cfg.set("sweep.whitelist-mode", null);
        cfg.set("sweep.whitelist-enabled", null);
        cfg.set("bins.move-notify-exempt", null);
        plugin.saveConfig();
        plugin.getLogger().info("已迁移旧白名单配置：清单 " + old.size() + " 种，ignore.enabled=" + exempt);
    }

    // ---- sweep ----
    public boolean sweepEnabled() {
        return cfg.getBoolean("sweep.enabled", true);
    }

    public int cleanInterval() {
        return Math.max(5, cfg.getInt("sweep.clean-interval", 300));
    }

    public int countdownStart() {
        return cfg.getInt("sweep.countdown-start", 60);
    }

    public List<Integer> countdownTimes() {
        return cfg.getIntegerList("sweep.countdown-times");
    }

    public boolean valuableEnabled() {
        return cfg.getBoolean("sweep.valuable-enabled", true);
    }

    /** Raw valuable-item names as stored in config (for display/commands). */
    public List<String> valuableNames() {
        return cfg.getStringList("sweep.valuable-items");
    }

    /**
     * Items considered valuable: the only ones notified when notify mode is
     * VALUABLE. Empty when the valuable switch is off, which disables the
     * VALUABLE tier everywhere (GUI shows it struck through, cycle skips it).
     */
    public Set<Material> valuableItems() {
        return valuableEnabled() ? parseMaterials(valuableNames()) : EnumSet.noneOf(Material.class);
    }

    public boolean ignoreEnabled() {
        return cfg.getBoolean("sweep.ignore.enabled", false);
    }

    /** Raw sweep-exempt names as stored in config (for display/commands). */
    public List<String> ignoreNames() {
        return cfg.getStringList("sweep.ignore.items");
    }

    /** Ground items exempt from sweep (only when the ignore switch is on). */
    public Set<Material> sweepExemptItems() {
        return ignoreEnabled() ? parseMaterials(ignoreNames()) : EnumSet.noneOf(Material.class);
    }

    private static Set<Material> parseMaterials(List<String> names) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String s : names) {
            Material m = Material.matchMaterial(s);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    /** World names (case-insensitive) excluded from sweep. */
    public Set<String> excludedWorlds() {
        Set<String> out = new HashSet<>();
        for (String s : cfg.getStringList("sweep.excluded-worlds")) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private boolean listAdd(String path, String materialName) {
        List<String> list = new ArrayList<>(cfg.getStringList(path));
        if (list.stream().anyMatch(materialName::equalsIgnoreCase)) {
            return false;
        }
        list.add(materialName);
        setList(path, list);
        return true;
    }

    private boolean listRemove(String path, String materialName) {
        List<String> list = new ArrayList<>(cfg.getStringList(path));
        if (!list.removeIf(materialName::equalsIgnoreCase)) {
            return false;
        }
        setList(path, list);
        return true;
    }

    private void setList(String path, List<String> list) {
        cfg.set(path, list);
        plugin.saveConfig();
    }

    public boolean valuableAdd(String materialName) {
        return listAdd("sweep.valuable-items", materialName);
    }

    public boolean valuableRemove(String materialName) {
        return listRemove("sweep.valuable-items", materialName);
    }

    public void valuableClear() {
        setList("sweep.valuable-items", new ArrayList<>());
    }

    public void setValuableEnabled(boolean v) {
        cfg.set("sweep.valuable-enabled", v);
        plugin.saveConfig();
    }

    public boolean ignoreAdd(String materialName) {
        return listAdd("sweep.ignore.items", materialName);
    }

    public boolean ignoreRemove(String materialName) {
        return listRemove("sweep.ignore.items", materialName);
    }

    public void ignoreClear() {
        setList("sweep.ignore.items", new ArrayList<>());
    }

    public void setIgnoreEnabled(boolean v) {
        cfg.set("sweep.ignore.enabled", v);
        plugin.saveConfig();
    }

    /** Persist clean interval (seconds); caller must re-apply sweep tasks. */
    public void setCleanInterval(int seconds) {
        cfg.set("sweep.clean-interval", seconds);
        plugin.saveConfig();
    }

    public boolean cleanExperienceOrbs() {
        return cfg.getBoolean("sweep.clean-experience-orbs", false);
    }

    public boolean thresholdEnabled() {
        return cfg.getBoolean("sweep.threshold-cleaning.enabled", false);
    }

    public int threshold() {
        return cfg.getInt("sweep.threshold-cleaning.threshold", 500);
    }

    public int thresholdCheckInterval() {
        return Math.max(5, cfg.getInt("sweep.threshold-cleaning.check-interval", 30));
    }

    // ---- collect ----
    public boolean collectEnabled() {
        return cfg.getBoolean("collect.enabled", true);
    }

    public boolean collectDefault() {
        return cfg.getBoolean("collect.default", true);
    }

    /** Master switch for death-drop collection; when false, drops stay on the ground. */
    public boolean collectOnDeath() {
        return cfg.getBoolean("collect.on-death", true);
    }

    // ---- bins ----
    public long privateTtlMs() {
        return TimeUtil.daysToMs(cfg.getDouble("bins.private-ttl-days", 3));
    }

    public long publicTtlMs() {
        return TimeUtil.daysToMs(cfg.getDouble("bins.public-ttl-days", 7));
    }

    public int privateMaxEntries() {
        return Math.max(0, cfg.getInt("bins.private-max-entries", 0));
    }

    public int publicMaxEntries() {
        return Math.max(0, cfg.getInt("bins.public-max-entries", 512));
    }

    public int expiryScanInterval() {
        return Math.max(5, cfg.getInt("bins.expiry-scan-interval", 60));
    }

    /** Per-player initial expiry notify mode; existing players keep their own choice. */
    public NotifyMode moveNotifyDefault() {
        return NotifyMode.fromValue(cfg.get("bins.move-notify", Boolean.TRUE));
    }

    public boolean moveNotifyChat() {
        return "CHAT".equalsIgnoreCase(cfg.getString("bins.move-notify-mode", "ACTIONBAR"));
    }

    /** Max distinct items named in expiry notify messages; 0 = count only. */
    public int notifyMaxShown() {
        return Math.max(0, cfg.getInt("bins.notify-max-shown", 3));
    }


    /** Warn the owner this many seconds before a private entry expires; 0 disables. */
    public int expiryWarnSeconds() {
        return Math.max(0, cfg.getInt("bins.expiry-warn-seconds", 60));
    }

    /** Preview window (minutes) for the expiry preview button. */
    public int expiryPreviewMinutes() {
        return Math.max(1, cfg.getInt("bins.expiry-preview-minutes", 10));
    }

    /** Per-player initial value for "expire = destroy"; existing players keep their own choice. */
    public boolean expiryDestroyDefault() {
        return cfg.getBoolean("bins.expiry-destroy", false);
    }

    public int destroyConfirmSeconds() {
        return Math.max(1, cfg.getInt("bins.destroy-confirm-seconds", 3));
    }

    // ---- gui ----
    public boolean sounds() {
        return cfg.getBoolean("gui.sounds", true);
    }

    public Material frameMaterial() {
        Material m = Material.matchMaterial(cfg.getString("gui.frame-material", "GRAY_STAINED_GLASS_PANE"));
        return m != null && m.isItem() ? m : Material.GRAY_STAINED_GLASS_PANE;
    }

    public Material darkBarMaterial() {
        Material m = Material.matchMaterial(cfg.getString("gui.dark-bar-material", "BLACK_STAINED_GLASS_PANE"));
        return m != null && m.isItem() ? m : Material.BLACK_STAINED_GLASS_PANE;
    }

    public Material icon(String key, Material def) {
        Material m = Material.matchMaterial(cfg.getString("gui.icons." + key, def.name()));
        return m != null && m.isItem() ? m : def;
    }

    /** Live re-render period (seconds) while a GUI window is open; 0 disables. */
    public int guiAutoRefreshSeconds() {
        return Math.max(0, cfg.getInt("gui.auto-refresh-seconds", 2));
    }

    // ---- admin ----
    public int clearpublicConfirmSeconds() {
        return Math.max(1, cfg.getInt("admin.clearpublic-confirm-seconds", 10));
    }

    // ---- messages ----
    public String msg(String key) {
        return cfg.getString("messages." + key, FALLBACK_MESSAGES.getOrDefault(key, ""));
    }

    /** Message with prefix prepended. */
    public String prefixed(String key) {
        return msg("prefix") + msg(key);
    }

    /** Defaults for message keys added after initial release; deployed configs lack them. */
    private static final Map<String, String> FALLBACK_MESSAGES = Map.ofEntries(
            Map.entry("sweep-now", "<green>已触发立即扫地</green>"),
            Map.entry("interval-set", "<green>清理间隔已设为 <aqua>{s}</aqua> 秒</green>"),
            Map.entry("list-header", "<aqua><bold>{list}</bold></aqua> {state} · <white>{n}</white> 种"),
            Map.entry("list-empty", "<gray>（空）</gray>"),
            Map.entry("list-add", "<green>已加入{list}：{item}</green>"),
            Map.entry("list-add-dup", "<yellow>{item} 已在{list}中</yellow>"),
            Map.entry("list-remove", "<green>已移出{list}：{item}</green>"),
            Map.entry("list-remove-missing", "<yellow>{list}中没有 {item}</yellow>"),
            Map.entry("list-clear", "<green>已清空{list}</green>"),
            Map.entry("list-bad-item", "<red>未知物品：{item}</red>"),
            Map.entry("list-on", "<green>{list}已启用</green>"),
            Map.entry("list-off", "<yellow>{list}已关闭</yellow>"),
            Map.entry("notify-set", "<green>已将 {player} 的到期提醒设为 {mode}</green>"),
            Map.entry("destroy-set", "<green>已将 {player} 的到期去向设为 {mode}</green>"),
            Map.entry("expiry-destroyed", "你回收站里的 {items} 已到期销毁"),
            Map.entry("notify-on", "<green>到期提醒已开启</green>"),
            Map.entry("notify-off", "<yellow>到期提醒已关闭</yellow>"),
            Map.entry("notify-valuable", "<gold>到期提醒：只提醒贵重物品</gold>"),
            Map.entry("expiry-mode-destroy", "<red>到期去向：自动销毁</red>"),
            Map.entry("expiry-mode-public", "<gold>到期去向：转公共回收站</gold>"),
            Map.entry("taken-back", "<green>已取回：</green><white>{item}</white>"),
            Map.entry("entry-expired", "<yellow>该条目已到期，将按到期设置处理</yellow>"),
            Map.entry("taken-all-public", "<green>已从公共回收站取走 <aqua>{n}</aqua> 件物品</green>"),
            Map.entry("expiry-warn-public", "<yellow>{item} 将在 {s} 秒后转入公共回收站</yellow>"),
            Map.entry("expiry-warn-destroy", "<yellow>{item} 将在 {s} 秒后销毁</yellow>"),
            Map.entry("preview-title", "<aqua><bold>即将到期（{m} 分钟内）</bold></aqua>"),
            Map.entry("preview-line", "<white>{item}</white> <dark_gray>·</dark_gray> <gray>剩 {t}</gray> <dark_gray>·</dark_gray> {dest}"),
            Map.entry("preview-more", "<gray>还有 {n} 件，略</gray>"),
            Map.entry("preview-empty", "<gray>{m} 分钟内没有即将到期的物品</gray>"),
            Map.entry("preview-name", "<aqua>到期预览</aqua>"),
            Map.entry("preview-lore", "<gray>查看 {m} 分钟内即将到期的物品</gray>"),
            Map.entry("preview-dest-public", "<gold>到期转公共</gold>"),
            Map.entry("preview-dest-destroy", "<red>到期销毁</red>"),
            Map.entry("preview-hover-line", "<gray>- {item} ×{n}</gray> <dark_gray>·</dark_gray> {color}剩 {t}"),
            Map.entry("preview-hover-more", "<dark_gray>…还有 {n} 条</dark_gray>"),
            Map.entry("preview-hover-empty", "<dark_gray>{m} 分钟内无到期</dark_gray>"));
}
