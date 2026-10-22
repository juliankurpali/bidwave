package com.bidwave.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bidwave.model.BidMessage;
import com.bidwave.util.Ids;
import com.bidwave.util.Json;
import com.bidwave.util.Log;
import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * POST /bids — validate body, enqueue SQS FIFO (group = lotId, dedupe = bidId).
 */
public class PlaceBidHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final SqsClient sqs;
    private final String queueUrl;

    public PlaceBidHandler() {
        this(SqsClient.create(), System.getenv("BID_QUEUE_URL"));
    }

    public PlaceBidHandler(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            String raw = ApiResponses.body(event);
            if (raw == null || raw.isBlank()) {
                return ApiResponses.badRequest("Missing JSON body");
            }

            JsonNode node = Json.mapper().readTree(raw);
            String lotId = text(node, "lotId");
            String bidder = text(node, "bidder");
            BigDecimal amount = amount(node);

            if (lotId == null || lotId.isBlank()) {
                return ApiResponses.badRequest("lotId is required");
            }
            if (bidder == null || bidder.isBlank()) {
                return ApiResponses.badRequest("bidder is required");
            }
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return ApiResponses.badRequest("amount must be a positive number");
            }

            String bidId = Ids.orNew(text(node, "bidId"));
            String correlationId = Ids.orNew(text(node, "correlationId"));
            String ts = Instant.now().toString();

            BidMessage message = new BidMessage(bidId, lotId, bidder, amount, ts, correlationId);

            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(Json.write(message))
                    .messageGroupId(lotId)
                    .messageDeduplicationId(bidId)
                    .build());

            Log.info("place_bid.enqueued", Log.fields(
                    "lotId", lotId,
                    "bidId", bidId,
                    "bidder", bidder,
                    "amount", amount.toPlainString(),
                    "correlationId", correlationId));

            return ApiResponses.created(Map.of(
                    "bidId", bidId,
                    "lotId", lotId,
                    "status", "QUEUED",
                    "correlationId", correlationId));
        } catch (IllegalArgumentException e) {
            return ApiResponses.badRequest(e.getMessage());
        } catch (Exception e) {
            Log.error("place_bid.failed", Log.fields("error", e.getMessage()));
            return ApiResponses.serverError("Failed to enqueue bid");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    private static BigDecimal amount(JsonNode node) {
        JsonNode v = node.get("amount");
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            if (v.isNumber()) {
                return v.decimalValue();
            }
            return new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
