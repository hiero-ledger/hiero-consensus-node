// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PRNG_RANGE;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.UtilPrngTransactionBody;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import com.hedera.node.app.service.util.impl.records.PrngStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.TestBase;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UtilPrngHandlerTest {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock
    private PrngStreamBuilder recordBuilder;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    @Mock
    private PureChecksContext pureChecksContext;

    private UtilPrngHandler subject;
    private UtilPrngTransactionBody txn;
    private static final Random random = new Random(92399921);
    private static final Bytes hash = Bytes.wrap(TestBase.randomBytes(random, 48));
    private static final Bytes nMinusThreeHash = hash;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        subject = new UtilPrngHandler();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(PrngStreamBuilder.class)).willReturn(recordBuilder);
        givenTxnWithoutRange();
    }

    @Test
    void pureChecksValidatesRange() {
        final var body = TransactionBody.newBuilder()
                .utilPrng(UtilPrngTransactionBody.newBuilder())
                .build();
        given(pureChecksContext.body()).willReturn(body);
        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidRange() {
        givenTxnWithRange(-10000);
        final var body = TransactionBody.newBuilder().utilPrng(txn).build();
        given(pureChecksContext.body()).willReturn(body);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_PRNG_RANGE));
    }

    @Test
    void acceptsPositiveAndZeroRange() {
        givenTxnWithRange(10000);
        final var body = TransactionBody.newBuilder().utilPrng(txn).build();
        given(pureChecksContext.body()).willReturn(body);

        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();

        givenTxnWithRange(0);
        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void acceptsNoRange() {
        givenTxnWithoutRange();
        final var body = TransactionBody.newBuilder().utilPrng(txn).build();
        given(pureChecksContext.body()).willReturn(body);

        assertThatCode(() -> subject.pureChecks(pureChecksContext)).doesNotThrowAnyException();
    }

    @Test
    void followsHappyPathWithNoRange() {
        givenTxnWithoutRange();
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(nMinusThreeHash);

        subject.handle(handleContext);

        verify(recordBuilder, never()).entropyNumber(anyInt());
        verify(recordBuilder).entropyBytes(hash);
    }

    @Test
    void calculateFeesHappyPath() {
        givenTxnWithoutRange();
        final var body = TransactionBody.newBuilder().utilPrng(txn).build();

        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(body);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);
        given(feeCalc.addBytesPerTransaction(anyLong())).willReturn(feeCalc);
        // The fees wouldn't be free in this scenario, but we don't care about the actual return
        // value here since we're using a mock calculator
        given(feeCalc.calculate()).willReturn(Fees.FREE);

        subject.calculateFees(feeCtx);

        verify(feeCalc).addBytesPerTransaction(0);
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                Integer.MIN_VALUE,
                -33,
                -32,
                -20,
                -10,
                -3,
                -2,
                -1,
                0,
                1,
                2,
                3,
                10,
                20,
                32,
                33,
                100,
                Integer.MAX_VALUE
            })
    void followsHappyPathWithRange(final int randomNumber) {
        givenTxnWithRange(20);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);

        // Make sure the random number given to the test ends up as the first 4 bytes of the hash
        given(blockRecordInfo.prngSeed()).willReturn(hashWithIntAtStart(randomNumber));

        // When we handle the transaction
        subject.handle(handleContext);

        // Then we find that the random number is in the range, and isn't negative!
        verify(recordBuilder).entropyNumber(anyInt());
        verify(recordBuilder, never()).entropyBytes(any());
    }

    /**
     * This test is used to verify that we handle negative numbers correctly. {@link #followsHappyPathWithRange(int)}
     * shows that we fall within the expected range, but this test shows that we get exactly the number we expect,
     * even when the random number itself was negative. The values used in this test come from the
     * {@code com.google.common.math.IntMath#mod(int, int)} method's documentation, which we used to use, and is used to
     * verify that we get the expected results even on our own implementation (which no longer requires that 3rd party
     * dependency). We also add some of our own test values to go through all possible permutations.
     *
     * @param rand
     * @param range
     * @param expected
     */
    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
            7, 4, 3
            -7, 4, 1
            -1, 4, 3
            -8, 4, 0
            8, 4, 0
            0, 4, 0
            1, 4, 1
            2, 4, 2
            3, 4, 3
            4, 4, 0
            5, 4, 1
            6, 4, 2
            7, 4, 3
            8, 4, 0
            """)
    void negativeRandomNumbersReturnPositiveWhenInRange(int rand, int range, int expected) {
        givenTxnWithRange(range);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);

        // Make sure the random number given to the test ends up as the first 4 bytes of the hash
        given(blockRecordInfo.prngSeed()).willReturn(hashWithIntAtStart(rand));

        // When we handle the transaction
        subject.handle(handleContext);

        // Then we find that the random number is in the range, and isn't negative!
        verify(recordBuilder).entropyNumber(expected);
        verify(recordBuilder, never()).entropyBytes(any());
    }

    @Test
    void followsHappyPathWithMaxIntegerRange() {
        givenTxnWithRange(Integer.MAX_VALUE);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(nMinusThreeHash);

        subject.handle(handleContext);

        verify(recordBuilder).entropyNumber(anyInt());
        verify(recordBuilder, never()).entropyBytes(any());
    }

    @Test
    void anyNegativeValueThrowsInPrecheck() {
        givenTxnWithRange(Integer.MIN_VALUE);
        final var body = TransactionBody.newBuilder().utilPrng(txn).build();
        given(pureChecksContext.body()).willReturn(body);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_PRNG_RANGE));
    }

    @Test
    void givenRangeZeroGivesBitString() {
        givenTxnWithRange(0);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(nMinusThreeHash);

        subject.handle(handleContext);

        verify(recordBuilder, never()).entropyNumber(anyInt());
        verify(recordBuilder).entropyBytes(hash);
    }

    @Test
    void nullBlockRecordInfoThrows() {
        givenTxnWithRange(0);
        given(handleContext.blockRecordInfo()).willReturn(null);

        assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(NullPointerException.class);

        verify(recordBuilder, never()).entropyNumber(anyInt());
        verify(recordBuilder, never()).entropyBytes(any());
    }

    @Test
    void nullHashFromRunningHashReturnsAllZeros() {
        givenTxnWithRange(0);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(null);

        subject.handle(handleContext);

        verify(recordBuilder, never()).entropyNumber(anyInt());
        verify(recordBuilder).entropyBytes(Bytes.wrap(new byte[48]));
    }

    @Test
    void emptyHashFromRunningHashReturnsAllZeros() {
        givenTxnWithRange(0);
        given(handleContext.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(Bytes.EMPTY);

        subject.handle(handleContext);

        verify(recordBuilder, never()).entropyNumber(anyInt());
        verify(recordBuilder).entropyBytes(Bytes.wrap(new byte[48]));
    }

    @Test
    void verifyModThrowException() {
        assertThatThrownBy(() -> UtilPrngHandler.mod(0, 0)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void verifyModHappyPath() {
        assertThatCode(() -> UtilPrngHandler.mod(2, 4)).doesNotThrowAnyException();

        assertThat(UtilPrngHandler.mod(2, 4)).isEqualTo(2);
    }

    private void givenTxnWithRange(int range) {
        txn = UtilPrngTransactionBody.newBuilder().range(range).build();
        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().utilPrng(txn).build());
    }

    private void givenTxnWithoutRange() {
        txn = UtilPrngTransactionBody.newBuilder().build();
        given(handleContext.body())
                .willReturn(TransactionBody.newBuilder().utilPrng(txn).build());
    }

    private Bytes hashWithIntAtStart(final int randomNumber) {
        final var builder = BufferedData.wrap(TestBase.randomBytes(random, 48));
        builder.writeInt(randomNumber);
        return builder.getBytes(0, 48);
    }
}
