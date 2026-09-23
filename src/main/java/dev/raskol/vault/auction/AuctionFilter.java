// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import dev.raskol.vault.RaskolVault;
import dev.raskol.vault.hook.TownyHook;

/**
 * Фильтр аукциона (1.2.5-a.1).
 * Все поля опциональны (null = нет фильтра по этому критерию).
 */
public record AuctionFilter(
        AuctionCategory category,
        AuctionLot.LotType type,
        String nation,
        Double minPrice,
        Double maxPrice,
        String searchQuery
) {

    public static AuctionFilter empty() {
        return new AuctionFilter(null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return category == null && type == null && nation == null
                && minPrice == null && maxPrice == null
                && (searchQuery == null || searchQuery.isBlank());
    }

    /** Проверяет лот на соответствие фильтру. */
    public boolean matches(AuctionLot lot, RaskolVault plugin) {
        if (category != null && category != AuctionCategory.of(lot.item())) return false;
        if (type != null && type != lot.type()) return false;
        if (nation != null && !nation.isEmpty()) {
            TownyHook towny = plugin.getTownyHook();
            if (towny == null || !towny.isAvailable()) return false;
            String sellerNation = towny.nationOf(lot.seller());
            if (sellerNation == null || !sellerNation.equalsIgnoreCase(nation)) return false;
        }
        double effectivePrice = effectivePrice(lot);
        if (minPrice != null && effectivePrice < minPrice) return false;
        if (maxPrice != null && effectivePrice > maxPrice) return false;
        if (searchQuery != null && !searchQuery.isBlank()) {
            String q = searchQuery.toLowerCase();
            String itemName = itemDisplayName(lot).toLowerCase();
            String sellerName = lot.sellerName() == null ? "" : lot.sellerName().toLowerCase();
            if (!itemName.contains(q) && !sellerName.contains(q)) return false;
        }
        return true;
    }

    /** Эффективная цена лота: buyout если есть, иначе текущая ставка или стартовая. */
    public static double effectivePrice(AuctionLot lot) {
        if (lot.type() != AuctionLot.LotType.AUCTION && lot.buyoutPrice() > 0) return lot.buyoutPrice();
        if (lot.currentBid() > 0) return lot.currentBid();
        return lot.startPrice();
    }

    /** Имя предмета для поиска: displayName если есть, иначе Material.name(). */
    public static String itemDisplayName(AuctionLot lot) {
        var meta = lot.item().getItemMeta();
        if (meta != null && meta.hasDisplayName() && !meta.getDisplayName().isBlank()) {
            return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        }
        return formatMaterialName(lot.item().getType().name());
    }

    private static String formatMaterialName(String name) {
        String[] parts = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** Возвращает новый фильтр с заменой одного поля. */
    public AuctionFilter withCategory(AuctionCategory c) {
        return new AuctionFilter(c, type, nation, minPrice, maxPrice, searchQuery);
    }
    public AuctionFilter withType(AuctionLot.LotType t) {
        return new AuctionFilter(category, t, nation, minPrice, maxPrice, searchQuery);
    }
    public AuctionFilter withNation(String n) {
        return new AuctionFilter(category, type, n, minPrice, maxPrice, searchQuery);
    }
    public AuctionFilter withMinPrice(Double p) {
        return new AuctionFilter(category, type, nation, p, maxPrice, searchQuery);
    }
    public AuctionFilter withMaxPrice(Double p) {
        return new AuctionFilter(category, type, nation, minPrice, p, searchQuery);
    }
    public AuctionFilter withSearch(String q) {
        return new AuctionFilter(category, type, nation, minPrice, maxPrice, q);
    }
}
