package com.codelensai;

import com.codelensai.service.IdempotencyService;
import com.codelensai.support.CodelensIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves delivery-level dedup: the first delivery for a UUID is processed, re-deliveries are ignored.
 * This is the "GitHub sends the same webhook twice — what happens?" guarantee.
 */
@CodelensIntegrationTest
class WebhookIdempotencyTest {

    @Autowired
    IdempotencyService idempotency;

    @Test
    void duplicateDeliveryIsIgnored() {
        String deliveryId = "test-uuid-" + UUID.randomUUID();
        assertTrue(idempotency.isFirstDelivery(deliveryId));   // 1st: process
        assertFalse(idempotency.isFirstDelivery(deliveryId));  // 2nd: ignore
        assertFalse(idempotency.isFirstDelivery(deliveryId));  // 3rd: ignore
    }
}
