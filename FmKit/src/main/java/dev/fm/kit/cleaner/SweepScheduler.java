package dev.fm.kit.cleaner;

import dev.fm.kit.FmKitPlugin;
import dev.fm.kit.bin.BinEntry;
import dev.fm.kit.util.TextUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Replaces the original sweep plugin's delete behavior: ground items are
 * collected into bins instead of being removed. Countdown always broadcasts
 * at scheduled times (original-plugin behavior), regardless of ground state.
 */
public final class SweepScheduler {

    private final FmKitPlugin plugin;
    private final AtomicBoolean cleaning = new AtomicBoolean(false);
    private ScheduledTask cleanTask;
    private ScheduledTask countdownTask;
    private ScheduledTask thresholdTask;
    private volatile boolean wanted = true;
    private volatile long nextCleanAt;
    private long lastBroadcastSec = -1;

    public SweepScheduler(FmKitPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isWanted() {
        return wanted;
    }

    public boolean isRunning() {
        return cleanTask != null;
    }

    /** Runtime toggle via /fmkitadmin sweep. */
    public void setRuntimeEnabled(boolean on) {
        wanted = on;
        applyConfig();
    }

    /** (Re)apply config: used at enable and after /fmkitadmin reload. */
    public synchronized void applyConfig() {
        stopTasks();
        if (wanted && plugin.settings().sweepEnabled()) {
            startTasks();
        }
    }

    public synchronized void stopTasks() {
        if (cleanTask != null) {
            cleanTask.cancel();
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        if (thresholdTask != null) {
            thresholdTask.cancel();
        }
        cleanTask = countdownTask = thresholdTask = null;
    }

    private synchronized void startTasks() {
        var s = plugin.settings();
        long intervalTicks = s.cleanInterval() * 20L;
        nextCleanAt = System.currentTimeMillis() + s.cleanInterval() * 1000L;
        lastBroadcastSec = -1;
        cleanTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> doClean(), intervalTicks, intervalTicks);
        countdownTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tickCountdown(), 20L, 20L);
        if (s.thresholdEnabled()) {
            long checkTicks = s.thresholdCheckInterval() * 20L;
            thresholdTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> checkThreshold(), checkTicks, checkTicks);
        }
    }

    private void tickCountdown() {
        long remainingSec = (nextCleanAt - System.currentTimeMillis()) / 1000L;
        if (remainingSec < 0) {
            return;
        }
        var s = plugin.settings();
        if (remainingSec <= s.countdownStart() && s.countdownTimes().contains((int) remainingSec)
                && lastBroadcastSec != remainingSec) {
            lastBroadcastSec = remainingSec;
            TextUtil.broadcast(TextUtil.apply(s.msg("countdown"), "s", String.valueOf(remainingSec)));
        }
    }

    private void checkThreshold() {
        var s = plugin.settings();
        Set<String> excluded = s.excludedWorlds();
        int count = 0;
        for (World w : Bukkit.getWorlds()) {
            if (!excluded.isEmpty() && excluded.contains(w.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            count += w.getEntitiesByClass(Item.class).size();
        }
        if (count > plugin.settings().threshold()) {
            triggerClean();
        }
    }

    /**
     * Manual/threshold-triggered clean: run immediately AND re-phase the periodic
     * tasks, so countdown broadcasts stay aligned with the next real clean
     * (fixed-rate tasks keep their original phase otherwise).
     */
    public void triggerClean() {
        doClean();
        applyConfig();
    }

    public void doClean() {
        if (!cleaning.compareAndSet(false, true)) {
            return;
        }
        var s = plugin.settings();
        Set<Material> whitelist = s.sweepExemptItems();
        boolean collectEnabled = s.collectEnabled();
        long publicTtl = s.publicTtlMs();
        long now = System.currentTimeMillis();
        nextCleanAt = now + s.cleanInterval() * 1000L;
        lastBroadcastSec = -1;

        // One done-token per spawned task plus one for the collect phase, released in
        // finally: the lock ALWAYS frees and the cleaned broadcast ALWAYS goes out,
        // even when collection or task spawning throws partway through.
        AtomicInteger collected = new AtomicInteger();
        AtomicInteger amount = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(1);
        Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) {
                cleaning.set(false);
                TextUtil.broadcast(TextUtil.apply(s.msg("cleaned"),
                        "n", String.valueOf(collected.get()), "m", String.valueOf(amount.get())));
            }
        };
        List<Item> items = new ArrayList<>();
        List<ExperienceOrb> orbs = new ArrayList<>();
        int spawned = 0;
        try {
            Set<String> excluded = s.excludedWorlds();
            for (World w : Bukkit.getWorlds()) {
                if (!excluded.isEmpty() && excluded.contains(w.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                items.addAll(w.getEntitiesByClass(Item.class));
                if (s.cleanExperienceOrbs()) {
                    orbs.addAll(w.getEntitiesByClass(ExperienceOrb.class));
                }
            }
            remaining.addAndGet(items.size() + orbs.size());

            for (ExperienceOrb orb : orbs) {
                orb.getScheduler().run(plugin, t -> {
                    try {
                        if (orb.isValid()) {
                            orb.remove();
                            collected.incrementAndGet();
                        }
                    } finally {
                        done.run();
                    }
                }, () -> done.run());
                spawned++;
            }

            for (Item item : items) {
                item.getScheduler().run(plugin, t -> {
                    try {
                        if (!item.isValid()) {
                            return;
                        }
                        ItemStack stack = item.getItemStack();
                        if (whitelist.contains(stack.getType())) {
                            return;
                        }
                        UUID thrower = item.getThrower();
                        if (collectEnabled && thrower != null && plugin.privateStore().isCollectEnabled(thrower)) {
                            plugin.privateStore().deposit(thrower, resolveName(thrower), stack);
                        } else {
                            String ownerName = thrower != null ? resolveName(thrower) : null;
                            // getTicksLived() is only legal on the entity's own region thread —
                            // which is exactly where this task runs. Entity ids are assigned per
                            // region thread, so ids of entities spawned across a region boundary
                            // do NOT encode creation order; entity age does. Negate so that older
                            // (earlier-arrived) items sort first under BY_ARRIVAL and get evicted
                            // first at capacity.
                            plugin.publicStore().add(new BinEntry(BinEntry.newId(), stack.clone(), ownerName,
                                    now, now + publicTtl, -(long) item.getTicksLived()));
                        }
                        collected.incrementAndGet();
                        amount.addAndGet(stack.getAmount());
                        item.remove();
                    } finally {
                        done.run();
                    }
                }, () -> done.run());
                spawned++;
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "FmKit: sweep round aborted mid-flight", t);
            // Settle tokens for tasks that never spawned so the lock still releases.
            int owed = items.size() + orbs.size() - spawned;
            for (int i = 0; i < owed; i++) {
                done.run();
            }
        } finally {
            done.run(); // collect-phase token
        }
    }

    private String resolveName(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            return p.getName();
        }
        return plugin.privateStore().knownName(uuid);
    }
}
