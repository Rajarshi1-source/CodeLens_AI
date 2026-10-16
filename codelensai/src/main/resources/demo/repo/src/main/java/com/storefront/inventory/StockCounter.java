package com.storefront.inventory;

import java.util.HashMap;
import java.util.Map;

public class StockCounter {

    private final Map<String, Stock> inventory = new HashMap<>();
    private int reserved;

    public boolean reserve(String sku, int quantity) {
        Stock stock = inventory.get(sku);
        if (stock == null || stock.available() < quantity) {
            return false;
        }
        stock.take(quantity);
        return true;
    }

    public int reserved() {
        return reserved;
    }
}
