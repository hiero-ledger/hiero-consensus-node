// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import java.util.function.Predicate;

public class TruePredicate implements Predicate {
    public static final Predicate INSTANCE = new TruePredicate();

    @Override
    public boolean test(Object ignored) {
        return true;
    }
}
