// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Holder GUI-страниц кошелька. Несёт тип страницы и контекст (валюты конверта,
 * номер страницы истории/кодекса), чтобы GuiListener маршрутизировал клики
 * без сравнения заголовков инвентаря.
 */
public final class WalletGuiHolder implements InventoryHolder {

    public enum Page {
        MAIN, CONVERT_FROM, CONVERT_TO, CONVERT_AMOUNT, CONVERT_CONFIRM,
        RATES, HISTORY, CABINET, CODEX
    }

    private final UUID owner;
    private final Page page;
    private final int pageIndex;
    private final String fromId;
    private final String toId;
    private final double amount;
    private Inventory inventory;

    private WalletGuiHolder(UUID owner, Page page, int pageIndex, String fromId, String toId, double amount) {
        this.owner = owner;
        this.page = page;
        this.pageIndex = pageIndex;
        this.fromId = fromId;
        this.toId = toId;
        this.amount = amount;
    }

    public static WalletGuiHolder of(UUID owner, Page page) {
        return new WalletGuiHolder(owner, page, 0, null, null, 0.0D);
    }

    public static WalletGuiHolder ofPage(UUID owner, Page page, int pageIndex) {
        return new WalletGuiHolder(owner, page, pageIndex, null, null, 0.0D);
    }

    public static WalletGuiHolder ofConvert(UUID owner, Page page, String fromId, String toId, double amount) {
        return new WalletGuiHolder(owner, page, 0, fromId, toId, amount);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    public UUID owner() {
        return owner;
    }

    public Page page() {
        return page;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public String fromId() {
        return fromId;
    }

    public String toId() {
        return toId;
    }

    public double amount() {
        return amount;
    }
}
