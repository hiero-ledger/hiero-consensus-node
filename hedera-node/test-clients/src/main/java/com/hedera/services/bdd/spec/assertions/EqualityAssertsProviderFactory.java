// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hedera.services.bdd.spec.HapiSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;

public class EqualityAssertsProviderFactory {
    public static <T> ErroringAssertsProvider<T> shouldBe(T expected) {
        return ignore -> actual -> {
            try {
                Assertions.assertEquals(expected, actual);
            } catch (Throwable t) {
                return Arrays.asList(t);
            }
            return Collections.EMPTY_LIST;
        };
    }

    public static <T> ErroringAssertsProvider<T> shouldNotBe(T unexpected) {
        return ignore -> actual -> {
            try {
                Assertions.assertNotEquals(unexpected, actual);
            } catch (Throwable t) {
                return Arrays.asList(t);
            }
            return Collections.EMPTY_LIST;
        };
    }

    public static <T> ErroringAssertsProvider<T> shouldBe(Function<HapiSpec, T> expectation) {
        return spec -> actual -> {
            try {
                T expected = expectation.apply(spec);
                Assertions.assertEquals(expected, actual);
            } catch (Throwable t) {
                return Arrays.asList(t);
            }
            return Collections.EMPTY_LIST;
        };
    }
}
