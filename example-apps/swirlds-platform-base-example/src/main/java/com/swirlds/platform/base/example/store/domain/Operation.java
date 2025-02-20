// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.util.List;

public record Operation(
        @Nullable String uuid,
        @NonNull List<OperationDetail> details,
        @NonNull Long timestamp,
        @NonNull OperationType type) {

    public record OperationDetail(
            @NonNull String itemId,
            @NonNull Integer amount,
            @NonNull BigDecimal unitaryPrice,
            @Nullable StockHandlingMode mode) {}
}
