package com.bidwave.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Auction lot stored in DynamoDB Lots table.
 */
public final class Lot {

    private final String lotId;
    private final String title;
    private final BigDecimal highBid;
    private final String highBidder;
    private final Instant startsAt;
    private final Instant endsAt;
    private final long version;

    public Lot(
            String lotId,
            String title,
            BigDecimal highBid,
            String highBidder,
            Instant startsAt,
            Instant endsAt,
            long version) {
        this.lotId = Objects.requireNonNull(lotId, "lotId");
        this.title = title;
        this.highBid = highBid != null ? highBid : BigDecimal.ZERO;
        this.highBidder = highBidder;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.version = version;
    }

    public String getLotId() {
        return lotId;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getHighBid() {
        return highBid;
    }

    public String getHighBidder() {
        return highBidder;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public long getVersion() {
        return version;
    }
}
