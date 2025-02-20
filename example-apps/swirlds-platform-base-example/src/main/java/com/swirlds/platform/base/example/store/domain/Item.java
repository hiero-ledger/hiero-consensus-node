// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public record Item(
        @NonNull String description,
        @NonNull String sku,
        @NonNull Integer minimumStockLevel,
        @NonNull String category,
        @Nullable String id) {}
