// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

@FunctionalInterface
public interface AssertingBiConsumer<T, U> {
    void accept(final T t, final U u) throws AssertionError;
}
