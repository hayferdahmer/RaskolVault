// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Категории предметов аукциона (1.2.5-a.1).
 * Средневековая классификация для удобства фильтрации.
 */
public enum AuctionCategory {

    WEAPONS("Оружие", Material.DIAMOND_SWORD,
            "Мечи, топоры, луки, арбалеты, трезубцы"),
    ARMOR("Доспехи", Material.DIAMOND_CHESTPLATE,
            "Шлемы, нагрудники, поножи, ботинки"),
    TOOLS("Инструменты", Material.DIAMOND_PICKAXE,
            "Кирки, лопаты, мотыги, удочки, ножницы"),
    RESOURCES("Ресурсы", Material.IRON_INGOT,
            "Руды, слитки, самоцветы, сырьё"),
    FOOD("Провиант", Material.COOKED_BEEF,
            "Еда, мясо, рыба, плоды"),
    POTIONS("Зелья", Material.POTION,
            "Зелья эффектов, бутылки"),
    BLOCKS("Стройматериалы", Material.OAK_PLANKS,
            "Блоки для строительства"),
    MISC("Разное", Material.CHEST,
            "Предметы без категории");

    private final String displayName;
    private final Material icon;
    private final String description;

    AuctionCategory(String displayName, Material icon, String description) {
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String description() { return description; }

    /** Определяет категорию предмета по его типу. */
    public static AuctionCategory of(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return MISC;
        Material type = item.getType();
        String name = type.name();

        // Оружие
        if (name.endsWith("_SWORD") || name.equals("BOW") || name.equals("CROSSBOW")
                || name.equals("TRIDENT") || name.equals("MACE")
                || (name.endsWith("_AXE") && !name.startsWith("WOODEN"))) {
            return WEAPONS;
        }
        // Доспехи
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.equals("TURTLE_HELMET") || name.equals("ELYTRA")) {
            return ARMOR;
        }
        // Инструменты
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || name.equals("FISHING_ROD")
                || name.equals("SHEARS") || name.equals("FLINT_AND_STEEL")
                || name.equals("COMPASS") || name.equals("CLOCK")
                || name.equals("LEAD") || name.equals("NAME_TAG")) {
            return TOOLS;
        }
        // Зелья
        if (type == Material.POTION || type == Material.SPLASH_POTION
                || type == Material.LINGERING_POTION
                || type == Material.GLASS_BOTTLE || type == Material.EXPERIENCE_BOTTLE) {
            return POTIONS;
        }
        // Провиант
        if (isFood(type)) {
            return FOOD;
        }
        // Ресурсы
        if (isResource(name)) {
            return RESOURCES;
        }
        // Блоки
        if (type.isBlock()) {
            return BLOCKS;
        }
        return MISC;
    }

    private static boolean isFood(Material type) {
        String name = type.name();
        return name.endsWith("_APPLE") || name.contains("BREAD") || name.contains("BEEF")
                || name.contains("CHICKEN") || name.contains("PORK") || name.contains("MUTTON")
                || name.contains("RABBIT") || name.contains("COD") || name.contains("SALMON")
                || name.contains("TROPICAL_FISH") || name.contains("PUFFERFISH")
                || name.equals("CARROT") || name.equals("GOLDEN_CARROT")
                || name.equals("POTATO") || name.equals("BAKED_POTATO")
                || name.equals("BEETROOT") || name.equals("BEETROOT_SOUP")
                || name.equals("MUSHROOM_STEW") || name.equals("PUMPKIN_PIE")
                || name.equals("MELON_SLICE") || name.equals("SWEET_BERRIES")
                || name.equals("GLOW_BERRIES") || name.equals("COOKIE")
                || name.equals("CAKE") || name.equals("HONEY_BOTTLE")
                || name.equals("DRIED_KELP") || name.equals("CHORUS_FRUIT")
                || name.equals("SUSPICIOUS_STEW") || name.equals("ROTTEN_FLESH")
                || name.equals("SPIDER_EYE") || name.equals("POISONOUS_POTATO");
    }

    private static boolean isResource(String name) {
        return name.endsWith("_ORE") || name.endsWith("_INGOT")
                || name.startsWith("RAW_") || name.endsWith("_GEM")
                || name.equals("DIAMOND") || name.equals("EMERALD")
                || name.equals("LAPIS_LAZULI") || name.equals("REDSTONE")
                || name.equals("QUARTZ") || name.equals("AMETHYST_SHARD")
                || name.equals("COAL") || name.equals("CHARCOAL")
                || name.equals("NETHERITE_SCRAP") || name.equals("ECHO_SHARD")
                || name.equals("COPPER_INGOT") || name.equals("IRON_NUGGET")
                || name.equals("GOLD_NUGGET") || name.equals("FLINT")
                || name.equals("BONE") || name.equals("STRING")
                || name.equals("FEATHER") || name.equals("LEATHER")
                || name.equals("GUNPOWDER") || name.equals("BLAZE_POWDER")
                || name.equals("BLAZE_ROD") || name.equals("GHAST_TEAR")
                || name.equals("ENDER_PEARL") || name.equals("ENDER_EYE")
                || name.equals("SLIME_BALL") || name.equals("MAGMA_CREAM")
                || name.equals("PHANTOM_MEMBRANE") || name.equals("SHULKER_SHELL")
                || name.equals("NAUTILUS_SHELL") || name.equals("HEART_OF_THE_SEA")
                || name.equals("NETHER_STAR") || name.equals("TOTEM_OF_UNDYING");
    }
}
