package dev.fm.kit.util;

/** Game-day time conversion. 1 game day = 20 real minutes = 24000 ticks. */
public final class TimeUtil {

    public static final long GAME_DAY_MS = 20L * 60L * 1000L;

    private TimeUtil() {
    }

    public static long daysToMs(double days) {
        return (long) (days * GAME_DAY_MS);
    }

    /** Human-readable remaining time, e.g. 3天2小时 / 12分5秒 / 40秒. */
    public static String format(long ms) {
        if (ms <= 0) {
            return "0秒";
        }
        long totalSec = ms / 1000;
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0) {
            return days + "天" + hours + "小时";
        }
        if (hours > 0) {
            return hours + "小时" + minutes + "分";
        }
        if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        }
        return seconds + "秒";
    }
}
