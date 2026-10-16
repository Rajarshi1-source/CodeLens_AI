package com.codelensai.service.cost;

import com.codelensai.config.PricingProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPricingTest {

    private final ModelPricing pricing = new ModelPricing(new PricingProperties(Map.of(
            "claude-opus-5-5", new PricingProperties.Price(new BigDecimal("4.00"), new BigDecimal("20.00")),
            "local-mock", new PricingProperties.Price(BigDecimal.ZERO, BigDecimal.ZERO))));

    @Test
    void costIsPerMillionTokens() {
        assertEquals(new BigDecimal("0.024000"), pricing.cost("claude-opus-5-5", 1000, 1000));
        assertEquals(new BigDecimal("4.000000"), pricing.cost("claude-opus-5-5", 1_000_000, 0));
    }

    @Test
    void unknownModelCostsZeroAndFreeModelIsDetected() {
        assertEquals(BigDecimal.ZERO, pricing.cost("mystery-model", 10, 10));
        assertTrue(pricing.isFree("local-mock"));
        assertFalse(pricing.isFree("claude-opus-5-5"));
        assertFalse(pricing.isFree("mystery-model"), "unpriced models are charged to the budget (safe default)");
    }
}
