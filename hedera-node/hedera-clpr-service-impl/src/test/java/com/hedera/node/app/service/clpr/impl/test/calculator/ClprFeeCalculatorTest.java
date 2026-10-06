// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REDACT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClprFeeCalculatorTest {

    /** 1/10th of a cent in tinycents (10^8 tinycents per cent ⇒ 10^7 per 0.1 cent). */
    private static final long FLAT_CLPR_BASE_FEE_TINYCENTS = 10_000_000L;

    private final ClprFeeCalculator subject =
            new ClprFeeCalculator(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE);

    @Test
    @DisplayName("getTransactionType returns the discriminant supplied at construction")
    void returnsTransactionType() {
        assertThat(subject.getTransactionType()).isEqualTo(TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE);
    }

    @Test
    @DisplayName("accumulateServiceFee sets service base fee when schedule has a matching entry")
    void setsServiceBaseFeeWhenScheduleHasEntry() {
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService("ClprService", makeServiceFee(CLPR_SUBMIT_BUNDLE, 12_345L)))
                .build();

        subject.accumulateServiceFee(TransactionBody.DEFAULT, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(12_345L);
    }

    @Test
    @DisplayName("accumulateServiceFee leaves fee unchanged when functionality not in schedule")
    void noOpWhenScheduleMissingEntry() {
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT.copyBuilder().build();

        subject.accumulateServiceFee(TransactionBody.DEFAULT, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isZero();
    }

    @ParameterizedTest(name = "{0} accumulates the flat 1/10¢ base fee")
    @MethodSource("clprTransactionOps")
    @DisplayName("Each CLPR transaction op accumulates the flat 1/10¢ base fee")
    void accumulatesFlatBaseFeeForEveryClprOp(
            final HederaFunctionality functionality, final TransactionBody.DataOneOfType dataOneOfType) {
        final var calculator = new ClprFeeCalculator(functionality, dataOneOfType);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService("ClprService", makeServiceFee(functionality, FLAT_CLPR_BASE_FEE_TINYCENTS)))
                .build();

        calculator.accumulateServiceFee(TransactionBody.DEFAULT, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(FLAT_CLPR_BASE_FEE_TINYCENTS);
    }

    /** All 9 CLPR transaction functionalities paired with their TransactionBody discriminants. */
    private static Stream<Arguments> clprTransactionOps() {
        return Stream.of(
                Arguments.of(
                        CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DataOneOfType.CLPR_UPDATE_LEDGER_CONFIGURATION),
                Arguments.of(CLPR_REGISTER_CHANNEL, TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL),
                Arguments.of(CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL),
                Arguments.of(CLPR_CLOSE_CHANNEL, TransactionBody.DataOneOfType.CLPR_CLOSE_CHANNEL),
                Arguments.of(CLPR_REGISTER_CONNECTOR, TransactionBody.DataOneOfType.CLPR_REGISTER_CONNECTOR),
                Arguments.of(CLPR_COMPLETE_CONNECTOR, TransactionBody.DataOneOfType.CLPR_COMPLETE_CONNECTOR),
                Arguments.of(CLPR_DEREGISTER_CONNECTOR, TransactionBody.DataOneOfType.CLPR_DEREGISTER_CONNECTOR),
                Arguments.of(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE),
                Arguments.of(CLPR_REDACT_MESSAGE, TransactionBody.DataOneOfType.CLPR_REDACT_MESSAGE));
    }
}
