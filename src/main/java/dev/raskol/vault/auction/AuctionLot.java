// © 2026 hayferdahmer — RASKOL Proprietary License v1.0. See LICENSE.
package dev.raskol.vault.auction;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Лот аукциона (1.2.5).
 */
public final class AuctionLot {

    public enum LotType { BUYOUT, AUCTION, AUCTION_BUYOUT }
    public enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }

    public record BidHistoryEntry(UUID bidder, String bidderName, double amount, long timestamp) {}

    private final String id;
    private final UUID seller;
    private final String sellerName;
    private final ItemStack item;
    private final LotType type;
    private final double startPrice;
    private final double buyoutPrice;
    private double currentBid;
    private UUID currentBidder;
    private String currentBidderName;
    private long createdAt;
    private long expiresAt;
    private Status status;
    private final List<BidHistoryEntry> bidHistory = new ArrayList<>();
    private String buyerName;
    private double finalPrice;

    public AuctionLot(String id, UUID seller, String sellerName, ItemStack item, LotType type,
                      double startPrice, double buyoutPrice, long createdAt, long expiresAt) {
        this.id = id; this.seller = seller; this.sellerName = sellerName;
        this.item = item; this.type = type;
        this.startPrice = startPrice; this.buyoutPrice = buyoutPrice;
        this.createdAt = createdAt; this.expiresAt = expiresAt;
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
    public double currentBid() { return currentBid; }
    public UUID currentBidder() { return currentBidder; }
    public String currentBidderName() { return currentBidderName; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public Status status() { return status; }
    public List<BidHistoryEntry> bidHistory() { return bidHistory; }
    public String buyerName() { return buyerName; }
    public double finalPrice() { return finalPrice; }

    public void placeBid(UUID bidder, String bidderName, double amount) {
        this.currentBid = amount;
        this.currentBidder = bidder;
        this.currentBidderName = bidderName;
        this.bidHistory.add(new BidHistoryEntry(bidder, bidderName, amount, System.currentTimeMillis()));
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
