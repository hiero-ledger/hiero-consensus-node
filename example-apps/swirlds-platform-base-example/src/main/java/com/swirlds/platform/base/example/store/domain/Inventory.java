// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import edu.umd.cs.findbugs.annotations.NonNull;

public record Inventory(@NonNull String itemId, @NonNull Integer amount) {}
