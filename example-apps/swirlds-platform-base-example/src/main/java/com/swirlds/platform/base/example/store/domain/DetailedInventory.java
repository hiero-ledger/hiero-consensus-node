// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record DetailedInventory(
        @NonNull Item item,
        @NonNull Integer amount,
        @NonNull List<StockDetail> stock,
        @NonNull List<Movement> movements) {

    public record Movement(@NonNull Date date, @NonNull Integer amount, @NonNull String operationUUID) {}

    public record StockDetail(@NonNull BigDecimal unitaryPrice, @NonNull Date date, @NonNull Integer quantity) {}
}
