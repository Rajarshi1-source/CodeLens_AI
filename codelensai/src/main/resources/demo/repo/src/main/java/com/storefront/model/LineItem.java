package com.storefront.model;

import java.math.BigDecimal;

public record LineItem(String sku, BigDecimal unitPrice, int quantity) {
}
