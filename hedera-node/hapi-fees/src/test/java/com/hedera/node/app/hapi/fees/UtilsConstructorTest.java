// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees;

import com.hedera.node.app.hapi.fees.usage.crypto.CryptoContextUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilsConstructorTest {
    private static final Set<Class<?>> toBeTested = new HashSet<>(Arrays.asList(CryptoContextUtils.class));

    @Test
    void throwsInConstructor() {
        for (final var clazz : toBeTested) {
            assertFor(clazz);
        }
    }

    private static final String UNEXPECTED_THROW = "Unexpected `%s` was thrown in `%s` constructor!";
    private static final String NO_THROW = "No exception was thrown in `%s` constructor!";

    private void assertFor(final Class<?> clazz) {
        try {
            final var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);

            constructor.newInstance();
        } catch (final InvocationTargetException expected) {
            final var cause = expected.getCause();
            Assertions.assertTrue(
                    cause instanceof UnsupportedOperationException, String.format(UNEXPECTED_THROW, cause, clazz));
            return;
        } catch (final Exception e) {
            Assertions.fail(String.format(UNEXPECTED_THROW, e, clazz));
        }
        Assertions.fail(String.format(NO_THROW, clazz));
    }
}
