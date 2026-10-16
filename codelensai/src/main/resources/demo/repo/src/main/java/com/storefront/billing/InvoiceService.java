package com.storefront.billing;

import com.storefront.payment.PriceCalculator;

import java.math.BigDecimal;

public class InvoiceService {

    private final PriceCalculator calculator;

    public InvoiceService(PriceCalculator calculator) {
        this.calculator = calculator;
    }

    public BigDecimal amountDue(Invoice invoice) {
        return calculator.calculateTotal(invoice.lineItems()).subtract(invoice.paid());
    }
}
