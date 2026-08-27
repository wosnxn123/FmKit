package dev.fm.shop.util;

/** Duration formatting for quota resets and price-recovery countdowns. */
public final class TimeUtil {

    private TimeUtil() {
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
