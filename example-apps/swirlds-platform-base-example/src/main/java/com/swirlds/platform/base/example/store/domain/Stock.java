// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

public record Stock(
        @NonNull String itemId,
        @NonNull BigDecimal unitaryPrice,
        @NonNull Long timestamp,
        @NonNull AtomicInteger remaining) {}
