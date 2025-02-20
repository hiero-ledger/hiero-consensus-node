// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigDecimal;
import java.util.Date;

public record Sale(
        @Nullable String uuid,
        @NonNull String itemId,
        @NonNull Integer amount,
        @NonNull BigDecimal salePrice,
        @Nullable StockHandlingMode mode,
        @Nullable Date date) {}
