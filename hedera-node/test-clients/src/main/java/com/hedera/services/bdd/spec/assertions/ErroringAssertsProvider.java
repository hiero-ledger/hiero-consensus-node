// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hedera.services.bdd.spec.HapiSpec;

@FunctionalInterface
public interface ErroringAssertsProvider<T> {
    ErroringAsserts<T> assertsFor(HapiSpec spec);
}
