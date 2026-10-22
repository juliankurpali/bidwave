package com.bidwave.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bidwave.model.Lot;
import com.bidwave.service.LotService;
import com.bidwave.util.Log;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GET /lots/{id} — public read of lot high bid.
 */
public class GetLotHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final LotService lotService;

    public GetLotHandler() {
        this(new LotService(
                DynamoDbClient.create(),
                System.getenv("LOTS_TABLE"),
                System.getenv("HISTORY_TABLE")));
    }

    public GetLotHandler(LotService lotService) {
        this.lotService = lotService;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            String lotId = ApiResponses.pathParam(event, "id");
            if (lotId == null || lotId.isBlank()) {
                return ApiResponses.badRequest("lot id path parameter is required");
            }

            Optional<Lot> lot = lotService.getLot(lotId);
            if (lot.isEmpty()) {
                return ApiResponses.notFound("Lot not found: " + lotId);
            }

            Lot l = lot.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lotId", l.getLotId());
            body.put("title", l.getTitle());
            body.put("highBid", l.getHighBid());
            body.put("highBidder", l.getHighBidder());
            body.put("startsAt", l.getStartsAt() != null ? l.getStartsAt().toString() : null);
            body.put("endsAt", l.getEndsAt() != null ? l.getEndsAt().toString() : null);
            body.put("version", l.getVersion());

            Log.info("get_lot.ok", Log.fields("lotId", lotId));
            return ApiResponses.ok(body);
        } catch (Exception e) {
            Log.error("get_lot.failed", Log.fields("error", e.getMessage()));
            return ApiResponses.serverError("Failed to get lot");
        }
    }
}
