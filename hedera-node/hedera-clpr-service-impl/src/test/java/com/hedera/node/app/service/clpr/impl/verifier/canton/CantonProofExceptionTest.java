// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CantonProofExceptionTest {
    private static final int ERROR_COUNT = 39;

    static List<Method> factories() {
        return Arrays.stream(CantonProofException.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType() == CantonProofException.class)
                .toList();
    }

    private static String capitalized(final String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Test
    void hasExactlyTheExpectedFactories() {
        final var factories = factories();
        assertThat(factories).hasSize(ERROR_COUNT * 2);
        final Set<String> names = factories.stream().map(Method::getName).collect(Collectors.toSet());
        assertThat(names).hasSize(ERROR_COUNT);
        // every name has both a no-arg and a (String) variant
        for (final var name : names) {
            assertThat(factories.stream().filter(m -> m.getName().equals(name)).map(Method::getParameterCount))
                    .containsExactlyInAnyOrder(0, 1);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    void eachFactoryReportsItsOwnNameAndResponseCode(final Method factory) throws Exception {
        final var expected = capitalized(factory.getName());
        final var e = (CantonProofException)
                (factory.getParameterCount() == 0 ? factory.invoke(null) : factory.invoke(null, "detail"));
        assertThat(e.errorName()).isEqualTo(expected);
        assertThat(e.responseCode()).isEqualTo(ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED);
        assertThat(e.getMessage())
                .isEqualTo(
                        factory.getParameterCount() == 0
                                ? "CantonVerifier: " + expected
                                : "CantonVerifier: " + expected + ": detail");
    }

    @Test
    void messageFormat() {
        assertThat(CantonProofException.rootHashMismatch()).hasMessage("CantonVerifier: RootHashMismatch");
        assertThat(CantonProofException.cantonProtoMalformed("truncated"))
                .hasMessage("CantonVerifier: CantonProtoMalformed: truncated");
    }
}
