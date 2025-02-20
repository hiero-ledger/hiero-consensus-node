// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class BaseErroringAsserts<T> implements ErroringAsserts<T> {
    private final List<Function<T, Optional<Throwable>>> tests;

    public BaseErroringAsserts(List<Function<T, Optional<Throwable>>> tests) {
        this.tests = tests;
    }

    @Override
    public List<Throwable> errorsIn(T instance) {
        return tests.stream().flatMap(t -> t.apply(instance).stream()).collect(toList());
    }
}
