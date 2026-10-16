package com.storefront.payment;

import com.storefront.model.LineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {

    private final PriceCalculator calc = new PriceCalculator();

    @Test
    void addsTax() {
        LineItem item = new LineItem("SKU-1", new BigDecimal("100.00"), 1);
        assertEquals(new BigDecimal("118.0000"), calc.calculateTotal(List.of(item)));
    }
}
