package com.storefront.model;

import java.util.List;

public record Cart(String customerId, List<LineItem> items, String discountCode) {
}
