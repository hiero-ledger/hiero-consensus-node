// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import java.lang.reflect.InvocationTargetException;

public class CommonTestUtils {
    private static final String UNEXPECTED_THROW = "Unexpected `%s` was thrown in `%s` constructor!";
    private static final String NO_THROW = "No exception was thrown in `%s` constructor!";

    public static void assertUnsupportedConstructor(final Class<?> clazz) {
        try {
            final var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);

            constructor.newInstance();
        } catch (final InvocationTargetException expected) {
            final var cause = expected.getCause();
            org.junit.jupiter.api.Assertions.assertTrue(
                    cause instanceof UnsupportedOperationException, String.format(UNEXPECTED_THROW, cause, clazz));
            return;
        } catch (final Exception e) {
            org.junit.jupiter.api.Assertions.fail(String.format(UNEXPECTED_THROW, e, clazz));
        }
        org.junit.jupiter.api.Assertions.fail(String.format(NO_THROW, clazz));
    }
}
