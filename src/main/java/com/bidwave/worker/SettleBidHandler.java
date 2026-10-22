package com.bidwave.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bidwave.model.BidMessage;
import com.bidwave.model.SettleStatus;
import com.bidwave.service.LotService;
import com.bidwave.util.Json;
import com.bidwave.util.Log;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQS FIFO consumer — settle each bid via LotService.
 */
public class SettleBidHandler implements RequestHandler<SQSEvent, Map<String, Object>> {

    private final LotService lotService;

    public SettleBidHandler() {
        this(new LotService(
                DynamoDbClient.create(),
                System.getenv("LOTS_TABLE"),
                System.getenv("HISTORY_TABLE")));
    }

    public SettleBidHandler(LotService lotService) {
        this.lotService = lotService;
    }

    @Override
    public Map<String, Object> handleRequest(SQSEvent event, Context context) {
        List<Map<String, String>> batchItemFailures = new ArrayList<>();

        for (SQSEvent.SQSMessage record : event.getRecords()) {
            try {
                BidMessage bid = parse(record.getBody());
                SettleStatus status = lotService.settle(bid);
                Log.info("settle_handler.done", Log.fields(
                        "messageId", record.getMessageId(),
                        "lotId", bid.getLotId(),
                        "bidId", bid.getBidId(),
                        "outcome", status.name()));
            } catch (Exception e) {
                Log.error("settle_handler.failed", Log.fields(
                        "messageId", record.getMessageId(),
                        "error", e.getMessage()));
                // Partial batch failure — return messageId so SQS retries only this one
                batchItemFailures.add(Map.of("itemIdentifier", record.getMessageId()));
            }
        }

        return Map.of("batchItemFailures", batchItemFailures);
    }

    private static BidMessage parse(String body) {
        Map<?, ?> map = Json.read(body, Map.class);
        String bidId = str(map, "bidId");
        String lotId = str(map, "lotId");
        String bidder = str(map, "bidder");
        BigDecimal amount = new BigDecimal(str(map, "amount"));
        String ts = str(map, "ts");
        String correlationId = str(map, "correlationId");
        return new BidMessage(bidId, lotId, bidder, amount, ts, correlationId);
    }

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return v.toString();
    }
}
