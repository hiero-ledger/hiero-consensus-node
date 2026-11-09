// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_ENDPOINT_PUBLICATION;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClprFeeCalculatorTest {

    private static final long BASE_FEE_TINYCENTS = 100_000_000L;
    private static final long STATE_BYTE_FEE_TINYCENTS = 1_000_000L;
    private static final long PROCESSING_BYTE_FEE_TINYCENTS = 10_000L;
    private static final long GAS_FEE_TINYCENTS = 852L;
    private static final int INCLUDED_STATE_BYTES = 8_192;

    private static final TransactionBody SUBMIT_BUNDLE = TransactionBody.newBuilder()
            .clprSubmitBundle(ClprSubmitBundleTransactionBody.newBuilder()
                    .bundlePayload(Bytes.wrap(new byte[1_000]))
                    .build())
            .build();
    private static final TransactionBody COMPLETE_CHANNEL = TransactionBody.newBuilder()
            .clprCompleteChannel(ClprCompleteChannelTransactionBody.newBuilder()
                    .configProofBytes(Bytes.wrap(new byte[INCLUDED_STATE_BYTES]))
                    .endpointManifestProofBytes(Bytes.wrap(new byte[500]))
                    .build())
            .build();

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

        subject.accumulateServiceFee(SUBMIT_BUNDLE, mock(SimpleFeeContext.class), feeResult, feeSchedule);

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

    @ParameterizedTest(name = "{0} accumulates only its base fee when the schedule lists no extras")
    @MethodSource("clprTransactionOps")
    @DisplayName("Each CLPR transaction op accumulates its base fee")
    void accumulatesBaseFeeForEveryClprOp(
            final HederaFunctionality functionality,
            final TransactionBody.DataOneOfType dataOneOfType,
            final TransactionBody body) {
        final var calculator = new ClprFeeCalculator(functionality, dataOneOfType);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .services(makeService("ClprService", makeServiceFee(functionality, BASE_FEE_TINYCENTS)))
                .build();

        calculator.accumulateServiceFee(body, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS);
    }

    @Test
    @DisplayName("ClprCompleteChannel bills verifier gas and stored bytes during handle")
    void completeChannelAccumulatesOnlyItsBaseFee() {
        final var calculator =
                new ClprFeeCalculator(CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL);
        final var feeResult = new FeeResult();
        final var feeSchedule = scheduleWith(
                CLPR_COMPLETE_CHANNEL,
                makeExtraIncluded(Extra.STATE_BYTES, INCLUDED_STATE_BYTES),
                makeExtraIncluded(Extra.GAS, 0));

        calculator.accumulateServiceFee(COMPLETE_CHANNEL, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS);
    }

    @Test
    @DisplayName("ClprSubmitBundle accrues the CLPR rate for every payload byte before handle")
    void submitBundleChargesPayloadBytesButNoFixedVerifierGas() {
        final var feeResult = new FeeResult();
        final var feeSchedule = scheduleWith(
                CLPR_SUBMIT_BUNDLE, makeExtraIncluded(Extra.PROCESSING_BYTES, 0), makeExtraIncluded(Extra.GAS, 0));

        subject.accumulateServiceFee(SUBMIT_BUNDLE, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(BASE_FEE_TINYCENTS + 1_000 * 1_000L);
    }

    private static FeeSchedule scheduleWith(
            final HederaFunctionality functionality, final org.hiero.hapi.support.fees.ExtraFeeReference... extras) {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.STATE_BYTES, STATE_BYTE_FEE_TINYCENTS),
                        makeExtraDef(Extra.PROCESSING_BYTES, PROCESSING_BYTE_FEE_TINYCENTS),
                        makeExtraDef(Extra.GAS, GAS_FEE_TINYCENTS))
                .services(makeService("ClprService", makeServiceFee(functionality, BASE_FEE_TINYCENTS, extras)))
                .build();
    }

    /** All CLPR transaction functionalities paired with their TransactionBody discriminants and a body. */
    private static Stream<Arguments> clprTransactionOps() {
        return Stream.of(
                Arguments.of(
                        CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DataOneOfType.CLPR_UPDATE_LEDGER_CONFIGURATION,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_REGISTER_CHANNEL,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL, COMPLETE_CHANNEL),
                Arguments.of(
                        CLPR_CLOSE_CHANNEL, TransactionBody.DataOneOfType.CLPR_CLOSE_CHANNEL, TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_REGISTER_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_COMPLETE_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DataOneOfType.CLPR_DEREGISTER_CONNECTOR,
                        TransactionBody.DEFAULT),
                Arguments.of(
                        CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DataOneOfType.CLPR_ENDPOINT_PUBLICATION,
                        TransactionBody.DEFAULT),
                Arguments.of(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE, SUBMIT_BUNDLE));
    }
}
