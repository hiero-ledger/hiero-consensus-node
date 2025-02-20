// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store;

import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.server.CrudHandler;
import com.swirlds.platform.base.example.server.HttpHandlerDefinition;
import com.swirlds.platform.base.example.server.HttpHandlerRegistry;
import com.swirlds.platform.base.example.store.metrics.StoreExampleMetrics;
import com.swirlds.platform.base.example.store.service.InventoryService;
import com.swirlds.platform.base.example.store.service.ItemService;
import com.swirlds.platform.base.example.store.service.OperationService;
import com.swirlds.platform.base.example.store.service.PurchaseService;
import com.swirlds.platform.base.example.store.service.SaleService;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Creates all HttpHandlers to expose Store-Rest-Api operations
 */
public class StoreExampleHandlerRegistry implements HttpHandlerRegistry {

    @NonNull
    @Override
    public Set<HttpHandlerDefinition> handlers(@NonNull final BaseContext context) {

        StoreExampleMetrics.registerMetrics(context.metrics());
        InitialData.populate();
        return Set.of(
                new CrudHandler<>("store/inventories", context, new InventoryService()),
                new CrudHandler<>("store/operations", context, new OperationService(context)),
                new CrudHandler<>("store/items", context, new ItemService()),
                new CrudHandler<>("store/sales", context, new SaleService(context)),
                new CrudHandler<>("store/purchases", context, new PurchaseService(context)));
    }
}
