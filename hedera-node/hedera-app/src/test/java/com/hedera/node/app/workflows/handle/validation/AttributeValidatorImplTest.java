// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.validation;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.node.app.spi.validation.AttributeValidator.MAX_NESTED_KEY_LEVELS;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttributeValidatorImplTest {
    private static final long maxLifetime = 3_000_000L;
    private static final byte[] MOCK_ED25519_KEY = "abcdefghabcdefghabcdefghabcdefgh".getBytes();

    @Mock(strictness = LENIENT)
    private HandleContext context;

    private AttributeValidatorImpl subject;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("entities.maxLifetime", maxLifetime)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        subject = new AttributeValidatorImpl(context);
    }

    @Test
    void memoCheckWorks() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("hedera.transaction.maxMemoUtf8Bytes", 100)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final char[] aaa = new char[101];
        Arrays.fill(aaa, 'a');
        final var memo = new String(aaa);

        assertThatCode(() -> subject.validateMemo("OK")).doesNotThrowAnyException();
        assertThatThrownBy(() -> subject.validateMemo(memo))
                .isInstanceOf(HandleException.class)
                .has(responseCode(MEMO_TOO_LONG));
        assertThatThrownBy(() -> subject.validateMemo("Not s\u0000 ok!"))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ZERO_BYTE_IN_STRING));
    }

    @Test
    void rejectsFutureExpiryImplyingSuperMaxLifetime() {
        given(context.consensusNow()).willReturn(Instant.ofEpochSecond(0L));
        Assertions.assertThatThrownBy(() -> subject.validateExpiry(maxLifetime + 1))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void allowsFutureExpiryBeforeMaxLifetime() {
        final var now = Instant.ofEpochSecond(1_234_567L);
        given(context.consensusNow()).willReturn(now);
        assertThatCode(() -> subject.validateExpiry(now.getEpochSecond() + 1)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnyNonFutureExpiry() {
        final var now = 1_234_567L;
        given(context.consensusNow()).willReturn(Instant.ofEpochSecond(now));
        assertThatThrownBy(() -> subject.validateExpiry(now))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_EXPIRATION_TIME));
    }

    @Test
    void rejectsBriefAutoRenewPeriod() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.autoRenewPeriod.minDuration", 1_000L)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        assertThatThrownBy(() -> subject.validateAutoRenewPeriod(55L))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void rejectsOverLongAutoRenewPeriod() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.autoRenewPeriod.minDuration", 1_000L)
                .withValue("ledger.autoRenewPeriod.maxDuration", 10_000L)
                .getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        assertThatThrownBy(() -> subject.validateAutoRenewPeriod(10_001L))
                .isInstanceOf(HandleException.class)
                .has(responseCode(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @Test
    void rejectsOverlyNestedKey() {
        final var acceptablyNested =
                nestKeys(Key.newBuilder(), MAX_NESTED_KEY_LEVELS - 1).build();
        final var overlyNested =
                nestKeys(Key.newBuilder(), MAX_NESTED_KEY_LEVELS).build();
        assertThatCode(() -> subject.validateKey(acceptablyNested)).doesNotThrowAnyException();
        assertThatThrownBy(() -> subject.validateKey(overlyNested))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BAD_ENCODING));
    }

    @Test
    void unsetKeysAreNotValid() {
        assertThatThrownBy(() -> subject.validateKey(Key.DEFAULT))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BAD_ENCODING));
    }

    private static Key.Builder nestKeys(final Key.Builder builder, final int additionalLevels) {
        if (additionalLevels == 0) {
            builder.ed25519(Bytes.wrap(MOCK_ED25519_KEY));
            return builder;
        }

        var nestedBuilder = Key.newBuilder();
        nestKeys(nestedBuilder, additionalLevels - 1);
        if (additionalLevels % 2 == 0) {
            builder.keyList(KeyList.newBuilder().keys(nestedBuilder.build()));
        } else {
            builder.thresholdKey(ThresholdKey.newBuilder()
                    .threshold(1)
                    .keys(KeyList.newBuilder().keys(nestedBuilder.build())));
        }
        return builder;
    }
}
