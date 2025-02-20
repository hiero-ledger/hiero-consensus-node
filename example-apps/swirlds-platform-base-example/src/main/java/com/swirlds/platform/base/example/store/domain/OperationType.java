// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.domain;

import java.util.function.BiFunction;

public enum OperationType {
    ADDITION(Math::addExact),
    DEDUCTION(Math::subtractExact);

    private final BiFunction<Integer, Integer, Integer> operation;

    OperationType(BiFunction<Integer, Integer, Integer> operation) {
        this.operation = operation;
    }

    public int apply(int arg1, int arg2) {
        return this.operation.apply(arg1, arg2);
    }
}
