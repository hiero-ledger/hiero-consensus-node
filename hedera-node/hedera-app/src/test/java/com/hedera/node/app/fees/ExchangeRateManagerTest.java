// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.transaction.ExchangeRateSet.PROTOBUF;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.fees.schemas.V0490FeeSchema;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExchangeRateManagerTest {

    private static final int hbarEquiv = 30_000;
    private static final int centEquiv = 120_000;
    private static final TimestampSeconds expirationTime =
            TimestampSeconds.newBuilder().seconds(150_000L).build();
    private static final ExchangeRate.Builder someRate =
            ExchangeRate.newBuilder().hbarEquiv(hbarEquiv).centEquiv(centEquiv).expirationTime(expirationTime);
    private static final ExchangeRate.Builder anotherRate = ExchangeRate.newBuilder()
            .hbarEquiv(hbarEquiv * 2)
            .centEquiv(centEquiv * 2)
            .expirationTime(expirationTime);
    ExchangeRateSet validRatesObj = ExchangeRateSet.newBuilder()
            .currentRate(someRate)
            .nextRate(anotherRate)
            .build();

    Bytes validRateBytes = ExchangeRateSet.PROTOBUF.toBytes(validRatesObj);
    ExchangeRateManager subject;

    @BeforeEach
    void setup() {
        final ConfigProvider configProvider = () -> new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
        subject = new ExchangeRateManager(configProvider);
        final var state = new FakeState();
        final var midnightRates = new AtomicReference<>(validRatesObj);
        state.addService(FeeService.NAME, Map.of(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID, midnightRates));
        subject.init(state, validRateBytes);
    }

    @Test
    void hasExpectedFields() throws ParseException {
        // when
        subject.update(validRateBytes, AccountID.DEFAULT);

        // expect
        final var curr = subject.exchangeRates().currentRateOrThrow();
        final var next = subject.exchangeRates().nextRateOrThrow();
        assertEquals(hbarEquiv, curr.hbarEquiv());
        assertEquals(hbarEquiv * 2, next.hbarEquiv());
        assertEquals(centEquiv, curr.centEquiv());
        assertEquals(centEquiv * 2, next.centEquiv());
        assertEquals(expirationTime.seconds(), curr.expirationTimeOrThrow().seconds());
        assertEquals(expirationTime.seconds(), next.expirationTimeOrThrow().seconds());
        assertEquals(PROTOBUF.parse(validRateBytes.toReadableSequentialData()), subject.exchangeRates());
    }

    @Test
    void updateWithInvalidExchangeRateBytes() {
        // given
        final var bytes = Bytes.wrap(new byte[] {0x06});

        // then
        assertThatThrownBy(() -> subject.update(bytes, AccountID.DEFAULT))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE));
    }

    @ParameterizedTest
    @MethodSource("provideConsensusTimesForActiveRate")
    void activeRateWorksAsExpected(Instant consensusTime, ExchangeRate expectedExchangeRate) {
        // given
        subject.update(validRateBytes, AccountID.DEFAULT);

        // when
        final var activeRate = subject.activeRate(consensusTime);

        // expect
        assertEquals(activeRate, expectedExchangeRate);
    }

    private static Stream<Arguments> provideConsensusTimesForActiveRate() {
        return Stream.of(
                Arguments.of(
                        Instant.ofEpochSecond(expirationTime.seconds() - 1L), // consensus time before expiration
                        someRate.build()),
                Arguments.of(
                        Instant.ofEpochSecond(expirationTime.seconds()), // consensus time at expiration
                        anotherRate.build()),
                Arguments.of(
                        Instant.ofEpochSecond(expirationTime.seconds() + 1L), // consensus time after expiration
                        anotherRate.build()));
    }
}
