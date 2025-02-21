// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.metrics;

import com.swirlds.common.metrics.DurationGauge;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.base.example.store.persistence.InventoryDao;
import com.swirlds.platform.base.example.store.persistence.ItemDao;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * All application
 */
public class StoreExampleMetrics {

    public static final String STORE_METRICS_CATEGORY = "store";

    private static final FunctionGauge.Config<Integer> ITEM_TOTAL = new FunctionGauge.Config<>(
                    STORE_METRICS_CATEGORY, "item_total", Integer.class, () -> ItemDao.getInstance()
                            .countAll())
            .withDescription("total number of items in the system");

    public static final Counter.Config OPERATION_TOTAL = new Counter.Config(STORE_METRICS_CATEGORY, "operation_total")
            .withDescription("total number of created operations with the api");
    public static final DurationGauge.Config OPERATION_TIME = new DurationGauge.Config(
                    STORE_METRICS_CATEGORY, "operation_time", ChronoUnit.NANOS)
            .withDescription("the time it takes to process an Operation");

    private static final FunctionGauge.Config<Long> ITEMS_BELOW_STOCK = new FunctionGauge.Config<>(
                    STORE_METRICS_CATEGORY,
                    "itemsBelowStock_total",
                    Long.class,
                    () -> ItemDao.getInstance().findAll().stream()
                            .filter(i -> {
                                assert i.id() != null;
                                return i.minimumStockLevel()
                                        < Objects.requireNonNull(InventoryDao.getInstance()
                                                        .findByItemId(i.id()))
                                                .amount();
                            })
                            .count())
            .withDescription("total number of items below minimum stock");

    /**
     * Register all metrics configurations
     */
    public static void registerMetrics(@NonNull final Metrics metrics) {
        metrics.getOrCreate(ITEM_TOTAL);
        metrics.getOrCreate(OPERATION_TOTAL);
        metrics.getOrCreate(OPERATION_TIME);
        metrics.getOrCreate(ITEMS_BELOW_STOCK);
    }
}
