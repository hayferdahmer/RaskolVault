// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Лот аукциона (1.2.6-b):
 *  - bidHistory ограничен 50 записями (последние 50 ставок)
 *  - extend() с абсолютным cap на 30 дней от createdAt
 */
public final class AuctionLot {

    public enum LotType { BUYOUT, AUCTION, AUCTION_BUYOUT }
    public enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }

    public record BidHistoryEntry(UUID bidder, String bidderName, double amount, long timestamp) {}

    private static final int MAX_BID_HISTORY = 50;
    private static final long MAX_EXTENSION_DAYS = 30L;

    private final String id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final LotType type;
    private final double startPrice;
    private final double buyoutPrice;
    private final String currencyId;
    private double currentBid;
    private UUID currentBidder;
    private String currentBidderName;
    private final long createdAt;
    private long expiresAt;
    private Status status;
    private final List<BidHistoryEntry> bidHistory = new ArrayList<>();
    private String buyerName;
    private double finalPrice;

    public AuctionLot(String id, UUID seller, String sellerName, ItemStack item, LotType type,
                      double startPrice, double buyoutPrice, String currencyId,
                      long createdAt, long expiresAt) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.type = type;
        this.startPrice = startPrice;
        this.buyoutPrice = buyoutPrice;
        this.currencyId = currencyId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = Status.ACTIVE;
        this.currentBid = 0.0D;
    }

    public String id() { return id; }
    public UUID seller() { return seller; }
    public String sellerName() { return sellerName; }
    public ItemStack item() { return item; }
    public LotType type() { return type; }
    public double startPrice() { return startPrice; }
    public double buyoutPrice() { return buyoutPrice; }
    public String currencyId() { return currencyId; }
    public double currentBid() { return currentBid; }
    public UUID currentBidder() { return currentBidder; }
    public String currentBidderName() { return currentBidderName; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public Status status() { return status; }
    public List<BidHistoryEntry> bidHistory() { return bidHistory; }
    public String buyerName() { return buyerName; }
    public double finalPrice() { return finalPrice; }

    /** Добавить ставку в историю (cap 50 записей). */
    public void placeBid(UUID bidder, String bidderName, double amount) {
        this.currentBid = amount;
        this.currentBidder = bidder;
        this.currentBidderName = bidderName;
        this.bidHistory.add(new BidHistoryEntry(bidder, bidderName, amount, System.currentTimeMillis()));
        while (this.bidHistory.size() > MAX_BID_HISTORY) {
            this.bidHistory.remove(0);
        }
    }

    /** Продлить лот на milliseconds, но не более MAX_EXTENSION_DAYS от createdAt. */
    public void extend(long milliseconds) {
        long maxExpires = createdAt + MAX_EXTENSION_DAYS * 86_400_000L;
        this.expiresAt = Math.min(this.expiresAt + milliseconds, maxExpires);
    }

    public void markSold(UUID buyer, String buyerName, double finalPrice) {
        this.status = Status.SOLD;
        this.buyerName = buyerName;
        this.finalPrice = finalPrice;
    }

    public void markExpired() { this.status = Status.EXPIRED; }
    public void markCancelled() { this.status = Status.CANCELLED; }
    public boolean isExpired(long now) { return now >= expiresAt; }

    public double minNextBid() {
        double base = currentBid > 0 ? currentBid : startPrice;
        return base + Math.max(1.0D, base * 0.05D);
    }
}
