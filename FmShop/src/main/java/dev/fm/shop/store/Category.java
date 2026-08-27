package dev.fm.shop.store;

import org.bukkit.Material;

/** A shop tab: display name, icon and config order. Items point back by id. */
public record Category(String id, String display, Material icon, int order) {
}
