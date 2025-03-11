// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.persistence;

import com.swirlds.platform.base.example.store.domain.Stock;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * in-memory simple data layer for Stock
 */
public class StockDao {

    private static class InstanceHolder {
        private static final StockDao INSTANCE = new StockDao();
    }

    public static @NonNull StockDao getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static final Map<String, ConcurrentLinkedQueue<Stock>> STOCKS = new ConcurrentHashMap<>();

    public @NonNull Stock save(final @NonNull Stock operation) {
        STOCKS.computeIfPresent(operation.itemId(), (k, s) -> {
            s.add(operation);
            return s;
        });
        STOCKS.putIfAbsent(operation.itemId(), new ConcurrentLinkedQueue<>(List.of(operation)));
        return operation;
    }

    public @NonNull List<Stock> getAll(final @NonNull String itemId) {
        final ConcurrentLinkedQueue<Stock> stocks = STOCKS.get(itemId);
        if (stocks == null) {
            return List.of();
        }
        return stocks.stream().sorted(Comparator.comparing(Stock::timestamp)).toList();
    }

    public @NonNull List<Stock> getAllWithRemaining(final @NonNull String itemId) {
        final ConcurrentLinkedQueue<Stock> stocks = STOCKS.get(itemId);
        if (stocks == null) {
            return List.of();
        }
        return stocks.stream()
                .filter(s -> s.remaining().get() > 0)
                .sorted(Comparator.comparing(Stock::timestamp))
                .toList();
    }

    public void deleteByItemId(final String itemId) {
        STOCKS.remove(itemId);
    }
}
