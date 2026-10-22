package com.bidwave.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bidwave.model.Lot;
import com.bidwave.service.LotService;
import com.bidwave.util.Log;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /lots — public list of live auctions for the floor UI.
 */
public class ListLotsHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final LotService lotService;

    public ListLotsHandler() {
        this(new LotService(
                DynamoDbClient.create(),
                System.getenv("LOTS_TABLE"),
                System.getenv("HISTORY_TABLE")));
    }

    public ListLotsHandler(LotService lotService) {
        this.lotService = lotService;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            List<Lot> lots = lotService.listLots();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Lot l : lots) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("lotId", l.getLotId());
                body.put("title", l.getTitle());
                body.put("highBid", l.getHighBid());
                body.put("highBidder", l.getHighBidder());
                body.put("startsAt", l.getStartsAt() != null ? l.getStartsAt().toString() : null);
                body.put("endsAt", l.getEndsAt() != null ? l.getEndsAt().toString() : null);
                body.put("version", l.getVersion());
                items.add(body);
            }

            Log.info("list_lots.ok", Log.fields("count", items.size()));
            return ApiResponses.ok(Map.of("lots", items));
        } catch (Exception e) {
            Log.error("list_lots.failed", Log.fields("error", e.getMessage()));
            return ApiResponses.serverError("Failed to list lots");
        }
    }
}
