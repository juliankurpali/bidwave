package com.bidwave.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bidwave.service.LotService;
import com.bidwave.util.Log;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;
import java.util.Map;

/**
 * GET /lots/{id}/bids — public bid / bidder list for a lot.
 */
public class ListLotBidsHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final LotService lotService;

    public ListLotBidsHandler() {
        this(new LotService(
                DynamoDbClient.create(),
                System.getenv("LOTS_TABLE"),
                System.getenv("HISTORY_TABLE")));
    }

    public ListLotBidsHandler(LotService lotService) {
        this.lotService = lotService;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            String lotId = ApiResponses.pathParam(event, "id");
            if (lotId == null || lotId.isBlank()) {
                return ApiResponses.badRequest("lot id path parameter is required");
            }
            if (lotService.getLot(lotId).isEmpty()) {
                return ApiResponses.notFound("Lot not found: " + lotId);
            }

            List<Map<String, Object>> bids = lotService.listBids(lotId);
            Log.info("list_bids.ok", Log.fields("lotId", lotId, "count", bids.size()));
            return ApiResponses.ok(Map.of("lotId", lotId, "bids", bids));
        } catch (Exception e) {
            Log.error("list_bids.failed", Log.fields("error", e.getMessage()));
            return ApiResponses.serverError("Failed to list bids");
        }
    }
}
