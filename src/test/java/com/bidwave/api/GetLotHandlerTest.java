package com.bidwave.api;

import com.bidwave.model.Lot;
import com.bidwave.service.LotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetLotHandlerTest {

    @Mock
    LotService lotService;

    @Test
    void returnsLot() {
        when(lotService.getLot("lot-1")).thenReturn(Optional.of(new Lot(
                "lot-1", "Vase", new BigDecimal("25"), "alice",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-12-31T00:00:00Z"),
                3L)));

        GetLotHandler handler = new GetLotHandler(lotService);
        Map<String, Object> event = Map.of("pathParameters", Map.of("id", "lot-1"));

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(200, response.get("statusCode"));
        assertTrue(response.get("body").toString().contains("\"highBid\":25"));
    }

    @Test
    void returns404WhenMissing() {
        when(lotService.getLot("nope")).thenReturn(Optional.empty());
        GetLotHandler handler = new GetLotHandler(lotService);
        Map<String, Object> event = Map.of("pathParameters", Map.of("id", "nope"));

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(404, response.get("statusCode"));
    }
}
