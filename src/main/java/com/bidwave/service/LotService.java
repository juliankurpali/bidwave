package com.bidwave.service;

import com.bidwave.model.BidMessage;
import com.bidwave.model.Lot;
import com.bidwave.model.SettleStatus;
import com.bidwave.util.Log;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conditional highBid updates and BidHistory audit trail.
 */
public class LotService {

    private final DynamoDbClient dynamoDb;
    private final String lotsTable;
    private final String historyTable;
    private final Clock clock;

    public LotService(DynamoDbClient dynamoDb, String lotsTable, String historyTable) {
        this(dynamoDb, lotsTable, historyTable, Instant::now);
    }

    public LotService(DynamoDbClient dynamoDb, String lotsTable, String historyTable, Clock clock) {
        this.dynamoDb = dynamoDb;
        this.lotsTable = lotsTable;
        this.historyTable = historyTable;
        this.clock = clock;
    }

    @FunctionalInterface
    public interface Clock {
        Instant now();
    }

    public Optional<Lot> getLot(String lotId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(lotsTable)
                .key(Map.of("lotId", AttributeValue.builder().s(lotId).build()))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromItem(response.item()));
    }

    /** List lots for the floor UI (small table — Scan is fine for demo). */
    public List<Lot> listLots() {
        List<Lot> lots = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest.Builder builder = ScanRequest.builder().tableName(lotsTable);
            if (startKey != null) {
                builder.exclusiveStartKey(startKey);
            }
            ScanResponse response = dynamoDb.scan(builder.build());
            if (response.hasItems()) {
                for (Map<String, AttributeValue> item : response.items()) {
                    lots.add(fromItem(item));
                }
            }
            startKey = response.lastEvaluatedKey();
            if (startKey != null && startKey.isEmpty()) {
                startKey = null;
            }
        } while (startKey != null);

        lots.sort(Comparator.comparing(Lot::getLotId));
        return lots;
    }

    /** Bid history for a lot (newest first). */
    public List<Map<String, Object>> listBids(String lotId) {
        List<Map<String, Object>> bids = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder builder = QueryRequest.builder()
                    .tableName(historyTable)
                    .keyConditionExpression("lotId = :lotId")
                    .expressionAttributeValues(Map.of(
                            ":lotId", AttributeValue.builder().s(lotId).build()))
                    .scanIndexForward(false);
            if (startKey != null) {
                builder.exclusiveStartKey(startKey);
            }
            QueryResponse response = dynamoDb.query(builder.build());
            if (response.hasItems()) {
                for (Map<String, AttributeValue> item : response.items()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("bidId", s(item, "bidId"));
                    row.put("lotId", s(item, "lotId"));
                    row.put("bidder", s(item, "bidder"));
                    row.put("amount", n(item, "amount"));
                    row.put("status", s(item, "status"));
                    row.put("settledAt", s(item, "settledAt"));
                    bids.add(row);
                }
            }
            startKey = response.lastEvaluatedKey();
            if (startKey != null && startKey.isEmpty()) {
                startKey = null;
            }
        } while (startKey != null);

        bids.sort((a, b) -> {
            String sa = String.valueOf(a.getOrDefault("settledAt", ""));
            String sb = String.valueOf(b.getOrDefault("settledAt", ""));
            return sb.compareTo(sa);
        });
        return bids;
    }

    /**
     * Settle a bid: idempotent on bidId, conditional highBid update, history row always written once.
     */
    public SettleStatus settle(BidMessage bid) {
        Instant now = clock.now();

        if (historyExists(bid.getLotId(), bid.getBidId())) {
            Log.info("settle.duplicate", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "outcome", SettleStatus.DUPLICATE.name()));
            return SettleStatus.DUPLICATE;
        }

        Optional<Lot> lotOpt = getLot(bid.getLotId());
        if (lotOpt.isEmpty()) {
            writeHistory(bid, SettleStatus.REJECTED_CLOSED, now);
            Log.warn("settle.lot_missing", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "outcome", SettleStatus.REJECTED_CLOSED.name()));
            return SettleStatus.REJECTED_CLOSED;
        }

        Lot lot = lotOpt.get();
        if (lot.getEndsAt() != null && !now.isBefore(lot.getEndsAt())) {
            writeHistory(bid, SettleStatus.REJECTED_CLOSED, now);
            Log.info("settle.closed", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "outcome", SettleStatus.REJECTED_CLOSED.name()));
            return SettleStatus.REJECTED_CLOSED;
        }

        if (bid.getAmount().compareTo(lot.getHighBid()) <= 0) {
            writeHistory(bid, SettleStatus.REJECTED_LOW, now);
            Log.info("settle.low", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "amount", bid.getAmount().toPlainString(),
                    "highBid", lot.getHighBid().toPlainString(),
                    "outcome", SettleStatus.REJECTED_LOW.name()));
            return SettleStatus.REJECTED_LOW;
        }

        try {
            tryAccept(bid, now);
            writeHistory(bid, SettleStatus.ACCEPTED, now);
            Log.info("settle.accepted", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "amount", bid.getAmount().toPlainString(),
                    "bidder", bid.getBidder(),
                    "outcome", SettleStatus.ACCEPTED.name()));
            return SettleStatus.ACCEPTED;
        } catch (ConditionalCheckFailedException e) {
            // Re-read to classify race: closed vs low
            Optional<Lot> after = getLot(bid.getLotId());
            SettleStatus status = classifyRace(bid, after, now);
            writeHistory(bid, status, now);
            Log.info("settle.race", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "correlationId", bid.getCorrelationId(),
                    "outcome", status.name()));
            return status;
        }
    }

    private SettleStatus classifyRace(BidMessage bid, Optional<Lot> after, Instant now) {
        if (after.isEmpty()) {
            return SettleStatus.REJECTED_CLOSED;
        }
        Lot lot = after.get();
        if (lot.getEndsAt() != null && !now.isBefore(lot.getEndsAt())) {
            return SettleStatus.REJECTED_CLOSED;
        }
        return SettleStatus.REJECTED_LOW;
    }

    private void tryAccept(BidMessage bid, Instant now) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":amount", AttributeValue.builder().n(bid.getAmount().toPlainString()).build());
        values.put(":bidder", AttributeValue.builder().s(bid.getBidder()).build());
        values.put(":now", AttributeValue.builder().s(now.toString()).build());
        values.put(":one", AttributeValue.builder().n("1").build());

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(lotsTable)
                .key(Map.of("lotId", AttributeValue.builder().s(bid.getLotId()).build()))
                .updateExpression("SET highBid = :amount, highBidder = :bidder, version = if_not_exists(version, :one) + :one")
                .conditionExpression("highBid < :amount AND endsAt > :now")
                .expressionAttributeValues(values)
                .build());
    }

    private boolean historyExists(String lotId, String bidId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(historyTable)
                .key(Map.of(
                        "lotId", AttributeValue.builder().s(lotId).build(),
                        "bidId", AttributeValue.builder().s(bidId).build()))
                .build());
        return response.hasItem() && !response.item().isEmpty();
    }

    private void writeHistory(BidMessage bid, SettleStatus status, Instant settledAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("lotId", AttributeValue.builder().s(bid.getLotId()).build());
        item.put("bidId", AttributeValue.builder().s(bid.getBidId()).build());
        item.put("bidder", AttributeValue.builder().s(bid.getBidder()).build());
        item.put("amount", AttributeValue.builder().n(bid.getAmount().toPlainString()).build());
        item.put("status", AttributeValue.builder().s(status.name()).build());
        item.put("settledAt", AttributeValue.builder().s(settledAt.toString()).build());
        if (bid.getCorrelationId() != null) {
            item.put("correlationId", AttributeValue.builder().s(bid.getCorrelationId()).build());
        }

        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(historyTable)
                    .item(item)
                    .conditionExpression("attribute_not_exists(bidId)")
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Concurrent settle of same bidId — treat as duplicate path already covered
            Log.info("settle.history_race", Log.fields(
                    "lotId", bid.getLotId(),
                    "bidId", bid.getBidId(),
                    "status", status.name()));
        }
    }

    static Lot fromItem(Map<String, AttributeValue> item) {
        return new Lot(
                s(item, "lotId"),
                s(item, "title"),
                n(item, "highBid"),
                s(item, "highBidder"),
                instant(item, "startsAt"),
                instant(item, "endsAt"),
                longVal(item, "version"));
    }

    private static String s(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null && v.s() != null ? v.s() : null;
    }

    private static BigDecimal n(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v == null || v.n() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(v.n());
    }

    private static Instant instant(Map<String, AttributeValue> item, String key) {
        String raw = s(item, key);
        return raw != null ? Instant.parse(raw) : null;
    }

    private static long longVal(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v == null || v.n() == null) {
            return 0L;
        }
        return Long.parseLong(v.n());
    }
}
