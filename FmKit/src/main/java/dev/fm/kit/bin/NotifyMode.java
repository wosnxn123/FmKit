package dev.fm.kit.bin;

import java.util.Locale;

/** Expiry notify mode: OFF = silent, VALUABLE = only valuable items, ALL = every entry. Click cycle: OFF → VALUABLE → ALL. */
public enum NotifyMode {
    OFF,
    VALUABLE,
    ALL;

    /** Parse a config/persisted value: boolean (legacy) or mode name (case-insensitive). Unknown → ALL. */
    public static NotifyMode fromValue(Object v) {
        if (v instanceof Boolean b) {
            return b ? ALL : OFF;
        }
        if (v instanceof String s) {
            return parse(s);
        }
        return ALL;
    }

    public static NotifyMode parse(String s) {
        if (s == null) {
            return ALL;
        }
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ALL;
        }
    }

    public NotifyMode next() {
        NotifyMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
