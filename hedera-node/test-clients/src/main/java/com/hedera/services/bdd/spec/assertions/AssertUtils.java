// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class AssertUtils {
    public static <T> ErroringAssertsProvider<List<T>> notEmpty() {
        return spec -> (List<T> instance) -> {
            try {
                Assertions.assertTrue(!instance.isEmpty(), "List shouldn't be empty!");
            } catch (Throwable t) {
                return List.of(t);
            }
            return Collections.EMPTY_LIST;
        };
    }

    @SafeVarargs
    public static <T> ErroringAssertsProvider<List<T>> inOrder(ErroringAssertsProvider<T>... providers) {
        return spec -> (List<T> instance) -> {
            try {
                Assertions.assertEquals(providers.length, instance.size(), "Bad list size!");
                for (int i = 0; i < providers.length; i++) {
                    List<Throwable> errorsHere = providers[i].assertsFor(spec).errorsIn(instance.get(i));
                    if (!errorsHere.isEmpty()) {
                        return errorsHere;
                    }
                }
            } catch (Throwable t) {
                return List.of(t);
            }
            return Collections.EMPTY_LIST;
        };
    }

    public static void rethrowSummaryError(Logger log, String summaryPrefix, List<Throwable> errors) throws Throwable {
        if (errors.isEmpty()) {
            return;
        }
        String summary = summaryPrefix
                + " :: "
                + errors.stream().map(Throwable::getMessage).collect(Collectors.joining(", "));
        throw new Exception(summary);
    }

    @FunctionalInterface
    interface ThrowingAssert {
        void assertThrowable(HapiSpec spec, Object o) throws Throwable;
    }
}
