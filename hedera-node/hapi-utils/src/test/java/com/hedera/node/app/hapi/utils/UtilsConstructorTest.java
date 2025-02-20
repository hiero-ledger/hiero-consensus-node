// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.node.app.hapi.utils.builder.RequestBuilder;
import com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.hapi.utils.sysfiles.ParsingUtils;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.HapiThrottleUtils;
import com.hedera.node.app.hapi.utils.sysfiles.serdes.ThrottlesJsonToProtoSerde;
import com.hedera.node.app.hapi.utils.sysfiles.validation.ErrorCodeUtils;
import com.hedera.node.app.hapi.utils.sysfiles.validation.ExpectedCustomThrottles;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilsConstructorTest {
    private static final Set<Class<?>> toBeTested = new HashSet<>(Arrays.asList(
            HapiThrottleUtils.class,
            ParsingUtils.class,
            CommonUtils.class,
            Ed25519Utils.class,
            ByteStringUtils.class,
            SignatureGenerator.class,
            ThrottlesJsonToProtoSerde.class,
            ErrorCodeUtils.class,
            ExpectedCustomThrottles.class,
            RequestBuilder.class,
            ConsensusServiceFeeBuilder.class));

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
