package com.storefront.payment;

import com.storefront.model.LineItem;

import java.math.BigDecimal;
import java.util.List;

public class PriceCalculator {

    private static final BigDecimal TAX_RATE = new BigDecimal("0.18");

    public BigDecimal calculateTotal(List<LineItem> items) {
        BigDecimal subtotal = items.stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return subtotal.add(subtotal.multiply(TAX_RATE));
    }
}
