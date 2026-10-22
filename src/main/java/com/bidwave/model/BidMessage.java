package com.bidwave.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Bid payload enqueued to SQS FIFO and settled by SettleBidHandler.
 */
public final class BidMessage {

    private final String bidId;
    private final String lotId;
    private final String bidder;
    private final BigDecimal amount;
    private final String ts;
    private final String correlationId;

    public BidMessage(
            String bidId,
            String lotId,
            String bidder,
            BigDecimal amount,
            String ts,
            String correlationId) {
        this.bidId = Objects.requireNonNull(bidId, "bidId");
        this.lotId = Objects.requireNonNull(lotId, "lotId");
        this.bidder = Objects.requireNonNull(bidder, "bidder");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.ts = ts;
        this.correlationId = correlationId;
    }

    public String getBidId() {
        return bidId;
    }

    public String getLotId() {
        return lotId;
    }

    public String getBidder() {
        return bidder;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getTs() {
        return ts;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
