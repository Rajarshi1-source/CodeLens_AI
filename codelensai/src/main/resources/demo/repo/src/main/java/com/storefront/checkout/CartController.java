package com.storefront.checkout;

import com.storefront.model.Cart;
import com.storefront.payment.PriceCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class CartController {

    private final PriceCalculator priceCalculator;

    public CartController(PriceCalculator priceCalculator) {
        this.priceCalculator = priceCalculator;
    }

    public BigDecimal checkout(Cart cart) {
        BigDecimal total = priceCalculator.calculateTotal(cart.items());
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
