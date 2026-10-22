package com.bidwave.local;

import com.bidwave.model.Lot;
import com.bidwave.model.SettleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local stand-in for DynamoDB Lots + BidHistory (no AWS).
 */
public final class InMemoryAuctionStore {

    private final Map<String, Lot> lots = new ConcurrentHashMap<>();
    private final Map<String, SettleStatus> historyByBidId = new ConcurrentHashMap<>();
    private final Map<String, List<BidRecord>> bidsByLot = new ConcurrentHashMap<>();

    public InMemoryAuctionStore() {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(24, ChronoUnit.HOURS);
        put(new Lot("lot-mug", "Vintage cloud mug", new BigDecimal("5"), "Auctioneer", start, end, 1));
        put(new Lot("lot-hoodie", "Signed BidWave hoodie", new BigDecimal("20"), "Auctioneer", start, end, 1));
        put(new Lot("lot-laptop", "Mystery refurbished laptop", new BigDecimal("50"), "Auctioneer", start, end, 1));
    }

    private void put(Lot lot) {
        lots.put(lot.getLotId(), lot);
        bidsByLot.putIfAbsent(lot.getLotId(), new CopyOnWriteArrayList<>());
    }

    public List<Lot> listLots() {
        List<Lot> copy = new ArrayList<>(lots.values());
        copy.sort(Comparator.comparing(Lot::getLotId));
        return copy;
    }

    public Optional<Lot> getLot(String lotId) {
        return Optional.ofNullable(lots.get(lotId));
    }

    public List<BidRecord> listBids(String lotId) {
        List<BidRecord> list = bidsByLot.getOrDefault(lotId, List.of());
        List<BidRecord> copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(BidRecord::settledAt).reversed());
        return Collections.unmodifiableList(copy);
    }

    public synchronized SettleResult placeBid(String bidId, String lotId, String bidder, BigDecimal amount) {
        Instant now = Instant.now();

        if (historyByBidId.containsKey(bidId)) {
            return new SettleResult(SettleStatus.DUPLICATE, lots.get(lotId));
        }

        Lot lot = lots.get(lotId);
        if (lot == null) {
            historyByBidId.put(bidId, SettleStatus.REJECTED_CLOSED);
            return new SettleResult(SettleStatus.REJECTED_CLOSED, null);
        }

        if (lot.getEndsAt() != null && !now.isBefore(lot.getEndsAt())) {
            record(lotId, bidId, bidder, amount, SettleStatus.REJECTED_CLOSED, now);
            return new SettleResult(SettleStatus.REJECTED_CLOSED, lot);
        }

        if (amount.compareTo(lot.getHighBid()) <= 0) {
            record(lotId, bidId, bidder, amount, SettleStatus.REJECTED_LOW, now);
            return new SettleResult(SettleStatus.REJECTED_LOW, lot);
        }

        Lot updated = new Lot(
                lot.getLotId(),
                lot.getTitle(),
                amount,
                bidder,
                lot.getStartsAt(),
                lot.getEndsAt(),
                lot.getVersion() + 1);
        lots.put(lotId, updated);
        record(lotId, bidId, bidder, amount, SettleStatus.ACCEPTED, now);
        return new SettleResult(SettleStatus.ACCEPTED, updated);
    }

    private void record(
            String lotId,
            String bidId,
            String bidder,
            BigDecimal amount,
            SettleStatus status,
            Instant settledAt) {
        historyByBidId.put(bidId, status);
        bidsByLot
                .computeIfAbsent(lotId, k -> new CopyOnWriteArrayList<>())
                .add(new BidRecord(bidId, lotId, bidder, amount, status.name(), settledAt));
    }

    public record BidRecord(
            String bidId,
            String lotId,
            String bidder,
            BigDecimal amount,
            String status,
            Instant settledAt) {
    }

    public record SettleResult(SettleStatus status, Lot lot) {
    }
}
