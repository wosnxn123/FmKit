package dev.fm.shop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** MiniMessage rendering + placeholder substitution. */
public final class TextUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /**
     * Section-sign form with the {@code §x§r§r§g§g§b§b} hex encoding that
     * CraftChatMessage understands; needed by the String-only
     * InventoryView#setTitle.
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexCharacter(LegacyComponentSerializer.HEX_CHAR)
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private TextUtil() {
    }

    public static Component mini(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    /** MiniMessage source -> legacy section string. */
    public static String legacy(String s) {
        return LEGACY.serialize(mini(s));
    }

    /** apply(tpl, "n", "3", "s", "60") replaces {n}/{s}. */
    public static String apply(String template, String... kv) {
        String out = template == null ? "" : template;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return out;
    }

    public static void msg(Player p, String s) {
        p.sendMessage(mini(s));
    }

    public static void action(Player p, String s) {
        p.sendActionBar(mini(s));
    }

    public static void send(CommandSender s, String str) {
        s.sendMessage(mini(str));
    }

    /** All-player announcement. Bukkit.broadcast relies on bukkit.broadcast.user
     *  subscriptions, but Folia's PaperPermissionManager only adds TRUE-default
     *  core permissions to the op bucket, so non-op players never receive it.
     *  Deliver directly. */
    public static void broadcast(String s) {
        Component c = mini(s);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(c);
        }
        Bukkit.getConsoleSender().sendMessage(c);
    }
}
