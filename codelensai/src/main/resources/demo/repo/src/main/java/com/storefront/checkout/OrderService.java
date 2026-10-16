package com.storefront.checkout;

import com.storefront.model.LineItem;
import com.storefront.payment.PriceCalculator;

import java.math.BigDecimal;
import java.util.List;

public class OrderService {

    private final PriceCalculator calculator;
    private final OrderRepository orders;

    public OrderService(PriceCalculator calculator, OrderRepository orders) {
        this.calculator = calculator;
        this.orders = orders;
    }

    public Order place(String customerId, List<LineItem> lines) {
        BigDecimal amount = calculator.calculateTotal(lines);
        return orders.save(new Order(customerId, lines, amount));
    }
}
