// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import java.util.List;

@FunctionalInterface
public interface ErroringAsserts<T> {
    List<Throwable> errorsIn(T instance);
}
