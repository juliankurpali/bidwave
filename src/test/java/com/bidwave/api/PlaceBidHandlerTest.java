package com.bidwave.api;

import com.bidwave.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceBidHandlerTest {

    @Mock
    SqsClient sqs;

    PlaceBidHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PlaceBidHandler(sqs, "https://sqs.example/bidwave.fifo");
    }

    @Test
    void enqueuesWithGroupAndDedupeIds() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());

        Map<String, Object> event = Map.of(
                "body", "{\"lotId\":\"lot-1\",\"bidder\":\"alice\",\"amount\":42.5,\"bidId\":\"bid-fixed\"}");

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(201, response.get("statusCode"));
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        assertEquals("lot-1", captor.getValue().messageGroupId());
        assertEquals("bid-fixed", captor.getValue().messageDeduplicationId());
        assertTrue(captor.getValue().messageBody().contains("\"bidId\":\"bid-fixed\""));
    }

    @Test
    void rejectsMissingAmount() {
        Map<String, Object> event = Map.of(
                "body", "{\"lotId\":\"lot-1\",\"bidder\":\"alice\"}");

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(400, response.get("statusCode"));
        assertTrue(response.get("body").toString().contains("amount"));
    }

    @Test
    void generatesBidIdWhenOmitted() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("m1").build());

        Map<String, Object> event = Map.of(
                "body", "{\"lotId\":\"lot-1\",\"bidder\":\"bob\",\"amount\":10}");

        Map<String, Object> response = handler.handleRequest(event, null);

        assertEquals(201, response.get("statusCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = Json.read(response.get("body").toString(), Map.class);
        assertTrue(body.get("bidId").toString().length() > 10);
    }
}
