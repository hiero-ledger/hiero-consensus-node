// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract.EXCHANGE_RATE_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract.TO_TINYBARS_SELECTOR;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract.TO_TINYCENTS_SELECTOR;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.google.common.primitives.Longs;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ExchangeRateSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSystemContractTest {
    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private MessageFrame frame;

    @Mock
    private ProxyWorldUpdater updater;

    @Mock
    private ContractsConfig contractsConfig;

    private ExchangeRateSystemContract subject;

    private MockedStatic<FrameUtils> frameUtils;

    @BeforeEach
    void setUp() {
        subject = new ExchangeRateSystemContract(gasCalculator);
        frameUtils = Mockito.mockStatic(FrameUtils.class);
    }

    @AfterEach
    void closeMocks() {
        frameUtils.close();
    }

    @Test
    void convertsPositiveNumberToTinybarsAsExpected() {
        givenRate(someRate);

        final var someInput = tinycentsInput(someTinycentAmount);
        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, someInput, frame);

        assertThat(result.output()).isEqualTo(unpackedBytesFor(someTinybarAmount));
    }

    @Test
    void convertsPositiveNumberToTinycentsAsExpected() {
        givenRate(someRate);

        final var positiveInput = tinybarsInput(someTinybarAmount);
        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, positiveInput, frame);

        assertThat(result.output()).isEqualTo(unpackedBytesFor(someTinycentAmount));
    }

    @Test
    void convertsZeroToTinybarsAsExpected() {
        givenRate(someRate);

        final var zeroInput = tinycentsInput(0);
        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, zeroInput, frame);

        assertThat(result.output()).isEqualTo(unpackedBytesFor(0));
    }

    @Test
    void inputCannotUnderflow() {
        final var underflowInput = tinycentsInput(Bytes.wrap(
                BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN).toByteArray()));

        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, underflowInput, frame);
        assertThat(result.output()).isEqualTo(Bytes.EMPTY);
        assertThat(result.result().getHaltReason()).hasValue(ExceptionalHaltReason.INVALID_OPERATION);
    }

    @Test
    void selectorMustBeFullyPresent() {
        final var fragmentSelector = Bytes.of(0xab);
        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, fragmentSelector, frame);
        assertThat(result.output()).isEqualTo(Bytes.EMPTY);
        assertThat(result.result().getHaltReason()).hasValue(ExceptionalHaltReason.INVALID_OPERATION);
    }

    @Test
    void selectorMustBeRecognized() {
        final var fragmentSelector = Bytes.of((byte) 0xab, (byte) 0xab, (byte) 0xab, (byte) 0xab);
        final var input = Bytes.concatenate(fragmentSelector, Bytes32.ZERO);
        final var result = subject.computeFully(EXCHANGE_RATE_CONTRACT_ID, input, frame);
        assertThat(result.output()).isEqualTo(Bytes.EMPTY);
        assertThat(result.result().getHaltReason()).hasValue(ExceptionalHaltReason.INVALID_OPERATION);
    }

    private static Bytes tinycentsInput(final long validAmount) {
        return input(TO_TINYBARS_SELECTOR, Bytes32.leftPad(Bytes.wrap(Longs.toByteArray(validAmount))));
    }

    private static Bytes tinybarsInput(final long validAmount) {
        return input(TO_TINYCENTS_SELECTOR, Bytes32.leftPad(Bytes.wrap(Longs.toByteArray(validAmount))));
    }

    private static Bytes tinycentsInput(final Bytes wordInput) {
        return input(TO_TINYBARS_SELECTOR, wordInput);
    }

    private static Bytes input(final int selector, final Bytes wordInput) {
        return Bytes.concatenate(Bytes.ofUnsignedInt(selector & 0xffffffffL), wordInput);
    }

    private static Bytes unpackedBytesFor(final long amount) {
        return unpackedBytesFor((byte) 0, amount);
    }

    private static Bytes unpackedBytesFor(final byte selector, final long amount) {
        final var word = new byte[32];
        final var bytes = Longs.toByteArray(amount);
        System.arraycopy(bytes, 0, word, 24, 8);
        if (selector != 0) {
            word[23] = selector;
        }
        return Bytes32.wrap(word);
    }

    private void givenRate(final ExchangeRate rate) {
        frameUtils.when(() -> proxyUpdaterFor(frame)).thenReturn(updater);
        given(updater.currentExchangeRate()).willReturn(rate);
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        given(contractsConfig.precompileExchangeRateGasCost()).willReturn(0L);
    }

    private static final int someHbarEquiv = 120;
    private static final int someCentEquiv = 100;
    private static final int someTinycentAmount = 123_456_000;
    private static final ExchangeRate someRate = ExchangeRate.newBuilder()
            .hbarEquiv(someHbarEquiv)
            .centEquiv(someCentEquiv)
            .build();
    private static final long someTinybarAmount = tinycentsToTinybars(someTinycentAmount, someRate);

    private static long tinycentsToTinybars(final long amount, final ExchangeRate rate) {
        final var hbarEquiv = rate.hbarEquiv();
        if (productWouldOverflow(amount, hbarEquiv)) {
            final var aMultiplier = BigInteger.valueOf(hbarEquiv);
            final var bDivisor = BigInteger.valueOf(rate.centEquiv());
            return BigInteger.valueOf(amount)
                    .multiply(aMultiplier)
                    .divide(bDivisor)
                    .longValueExact();
        }
        return amount * hbarEquiv / rate.centEquiv();
    }

    private static boolean productWouldOverflow(final long multiplier, final long multiplicand) {
        if (multiplicand == 0) {
            return false;
        }
        final var maxMultiplier = Long.MAX_VALUE / multiplicand;
        return multiplier > maxMultiplier;
    }
}
