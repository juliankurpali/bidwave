package com.bidwave.service;

import com.bidwave.model.BidMessage;
import com.bidwave.model.SettleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotServiceTest {

    private static final String LOTS = "Lots";
    private static final String HISTORY = "BidHistory";
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant ENDS_LATER = Instant.parse("2026-06-01T13:00:00Z");
    private static final Instant ENDS_PAST = Instant.parse("2026-06-01T11:00:00Z");

    @Mock
    DynamoDbClient dynamoDb;

    LotService service;

    @BeforeEach
    void setUp() {
        service = new LotService(dynamoDb, LOTS, HISTORY, () -> NOW);
    }

    @Test
    void higherBidIsAccepted() {
        stubHistoryMissing();
        stubLotGet(lotItem("lot-1", "100", ENDS_LATER));
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse.builder().build());
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b1", "lot-1", "alice", "150"));

        assertEquals(SettleStatus.ACCEPTED, status);
        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(update.capture());
        assertTrue(update.getValue().conditionExpression().contains("highBid < :amount"));
        assertTrue(update.getValue().conditionExpression().contains("endsAt > :now"));

        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(put.capture());
        assertEquals("ACCEPTED", put.getValue().item().get("status").s());
    }

    @Test
    void lowerBidIsRejectedLow() {
        stubHistoryMissing();
        stubLotGet(lotItem("lot-1", "200", ENDS_LATER));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b2", "lot-1", "bob", "150"));

        assertEquals(SettleStatus.REJECTED_LOW, status);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
        assertHistoryStatus(SettleStatus.REJECTED_LOW);
    }

    @Test
    void equalBidIsRejectedLow() {
        stubHistoryMissing();
        stubLotGet(lotItem("lot-1", "100", ENDS_LATER));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b3", "lot-1", "carol", "100"));

        assertEquals(SettleStatus.REJECTED_LOW, status);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void closedLotIsRejectedClosed() {
        stubHistoryMissing();
        stubLotGet(lotItem("lot-1", "50", ENDS_PAST));
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b4", "lot-1", "dave", "999"));

        assertEquals(SettleStatus.REJECTED_CLOSED, status);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
        assertHistoryStatus(SettleStatus.REJECTED_CLOSED);
    }

    @Test
    void missingLotIsRejectedClosed() {
        stubHistoryMissing();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            if (HISTORY.equals(req.tableName())) {
                return GetItemResponse.builder().build();
            }
            return GetItemResponse.builder().build();
        });
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b5", "missing", "eve", "10"));

        assertEquals(SettleStatus.REJECTED_CLOSED, status);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void duplicateBidIdIsNoOp() {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            if (HISTORY.equals(req.tableName())) {
                Map<String, AttributeValue> existing = new HashMap<>();
                existing.put("lotId", AttributeValue.builder().s("lot-1").build());
                existing.put("bidId", AttributeValue.builder().s("b-dup").build());
                existing.put("status", AttributeValue.builder().s("ACCEPTED").build());
                return GetItemResponse.builder().item(existing).build();
            }
            return GetItemResponse.builder().build();
        });

        SettleStatus status = service.settle(bid("b-dup", "lot-1", "frank", "500"));

        assertEquals(SettleStatus.DUPLICATE, status);
        verify(dynamoDb, never()).updateItem(any(UpdateItemRequest.class));
        verify(dynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void raceOnUpdateClassifiesAsRejectedLow() {
        stubHistoryMissing();
        AtomicInteger lotGets = new AtomicInteger();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            if (HISTORY.equals(req.tableName())) {
                return GetItemResponse.builder().build();
            }
            // First get: highBid 100; after race: highBid 200 (someone else won)
            int n = lotGets.getAndIncrement();
            String high = n == 0 ? "100" : "200";
            return GetItemResponse.builder().item(lotItem("lot-1", high, ENDS_LATER)).build();
        });
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("race").build());
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b6", "lot-1", "gina", "150"));

        assertEquals(SettleStatus.REJECTED_LOW, status);
        assertHistoryStatus(SettleStatus.REJECTED_LOW);
    }

    @Test
    void raceOnClosedLotClassifiesAsRejectedClosed() {
        stubHistoryMissing();
        AtomicInteger lotGets = new AtomicInteger();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            if (HISTORY.equals(req.tableName())) {
                return GetItemResponse.builder().build();
            }
            int n = lotGets.getAndIncrement();
            Instant ends = n == 0 ? ENDS_LATER : ENDS_PAST;
            return GetItemResponse.builder().item(lotItem("lot-1", "100", ends)).build();
        });
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("closed").build());
        when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.PutItemResponse.builder().build());

        SettleStatus status = service.settle(bid("b7", "lot-1", "hank", "150"));

        assertEquals(SettleStatus.REJECTED_CLOSED, status);
    }

    private void stubHistoryMissing() {
        // Handled inside stubLotGet / custom stubs via table name
    }

    private void stubLotGet(Map<String, AttributeValue> lot) {
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenAnswer(inv -> {
            GetItemRequest req = inv.getArgument(0);
            if (HISTORY.equals(req.tableName())) {
                return GetItemResponse.builder().build();
            }
            return GetItemResponse.builder().item(lot).build();
        });
    }

    private void assertHistoryStatus(SettleStatus expected) {
        ArgumentCaptor<PutItemRequest> put = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb, times(1)).putItem(put.capture());
        assertEquals(expected.name(), put.getValue().item().get("status").s());
    }

    private static BidMessage bid(String bidId, String lotId, String bidder, String amount) {
        return new BidMessage(bidId, lotId, bidder, new BigDecimal(amount), NOW.toString(), "corr-1");
    }

    private static Map<String, AttributeValue> lotItem(String lotId, String highBid, Instant endsAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("lotId", AttributeValue.builder().s(lotId).build());
        item.put("title", AttributeValue.builder().s("Charity lot").build());
        item.put("highBid", AttributeValue.builder().n(highBid).build());
        item.put("highBidder", AttributeValue.builder().s("seed").build());
        item.put("startsAt", AttributeValue.builder().s("2026-06-01T10:00:00Z").build());
        item.put("endsAt", AttributeValue.builder().s(endsAt.toString()).build());
        item.put("version", AttributeValue.builder().n("1").build());
        return item;
    }
}
