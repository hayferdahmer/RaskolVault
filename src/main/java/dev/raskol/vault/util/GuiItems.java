// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Билдеры предметов для GUI.
 * Заменяет дубли item(...) / pane() / frame() / c() из AuctionGui, BankGui, WalletGui и др.
 */
public final class GuiItems {

    private GuiItems() {}

    /** Цветовой хелпер: '&6Золото' → §6Золото. */
    public static String c(String s) {
        if (s == null) return null;
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Создать предмет с displayName и lore (с авто-цветом и скрытием аттрибутов). */
    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(c(name));
            if (lore != null && !lore.isEmpty()) {
                List<String> colored = new ArrayList<>(lore.size());
                for (String line : lore) colored.add(c(line));
                meta.setLore(colored);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                    ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Пустая стеклянная панель-разделитель. */
    public static ItemStack pane() {
        return item(Material.BLACK_STAINED_GLASS_PANE, "&8·", List.of());
    }

    /** Рамка из панелей: верхняя (0..8) и нижняя (45..53) полосы. */
    public static void frame54(Inventory inv) {
        for (int i = 0; i < 9; i++) inv.setItem(i, pane());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane());
    }

    /** Иконка валюты по ID (GLD/RAS/VLR). */
    public static Material currencyIcon(String id) {
        if (id == null) return Material.GOLD_NUGGET;
        return switch (id.toUpperCase(java.util.Locale.ROOT)) {
            case "GLD" -> Material.GOLD_INGOT;
            case "RAS" -> Material.SUNFLOWER;
            case "VLR" -> Material.GOLDEN_HELMET;
            default -> Material.GOLD_NUGGET;
        };
    }
}
