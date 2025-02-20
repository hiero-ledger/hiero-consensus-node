// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.service;

import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.server.CrudService;
import com.swirlds.platform.base.example.server.DataTransferUtils;
import com.swirlds.platform.base.example.store.domain.Operation;
import com.swirlds.platform.base.example.store.domain.Operation.OperationDetail;
import com.swirlds.platform.base.example.store.domain.OperationType;
import com.swirlds.platform.base.example.store.domain.Purchase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Creates Purchases
 */
public class PurchaseService extends CrudService<Purchase> {

    private final OperationService operationService;

    public PurchaseService(@NonNull final BaseContext context) {
        super(Purchase.class);
        this.operationService = new OperationService(context);
    }

    @NonNull
    @Override
    public Purchase create(@NonNull final Purchase purchase) {
        final OperationDetail operationDetail =
                new OperationDetail(purchase.itemId(), purchase.amount(), purchase.buyPrice(), null);
        final Operation operation =
                operationService.create(new Operation(null, List.of(operationDetail), -1L, OperationType.ADDITION));
        return new Purchase(
                operation.uuid(),
                purchase.itemId(),
                purchase.amount(),
                purchase.buyPrice(),
                DataTransferUtils.fromEpoc(operation.timestamp()));
    }
}
