// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.calculator;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_CLOSE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_COMPLETE_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_DEREGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CHANNEL;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_REGISTER_CONNECTOR;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_UPDATE_LEDGER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.clpr.ClprCompleteChannelTransactionBody;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.calculator.ClprFeeCalculator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClprFeeCalculatorTest {

    /** 1/10th of a cent in tinycents (10^8 tinycents per cent ⇒ 10^7 per 0.1 cent). */
    private static final long FLAT_CLPR_BASE_FEE_TINYCENTS = 10_000_000L;

    private static final long SUBMIT_BUNDLE_BASE_FEE = 100L;
    private static final long BYTE_FEE = 10L;
    private static final long GAS_FEE = 3L;

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

    @Test
    @DisplayName("ClprSubmitBundle charges bundle bytes and ignores a legacy verifier-gas extra")
    void submitBundleChargesBytesWithoutVerifierGas() {
        final var feeResult = new FeeResult();
        final var simpleFeeContext = simpleFeeContextWithVerifierGasLimit(1_000L);

        subject.accumulateServiceFee(
                submitBundleOfBytes(50),
                simpleFeeContext,
                feeResult,
                scheduleWithExtras(Extra.CLPR_BUNDLE_BYTES, Extra.GAS));

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE + 50 * BYTE_FEE);
    }

    @Test
    @DisplayName("ClprSubmitBundle charges no extras its schedule entry does not list")
    void submitBundleSkipsUnscheduledExtras() {
        final var feeResult = new FeeResult();
        final var simpleFeeContext = simpleFeeContextWithVerifierGasLimit(1_000L);

        subject.accumulateServiceFee(
                submitBundleOfBytes(50), simpleFeeContext, feeResult, scheduleWithExtras(Extra.CLPR_BUNDLE_BYTES));

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE + 50 * BYTE_FEE);
    }

    @Test
    @DisplayName("ClprSubmitBundle skips the verifier-gas extra when computing fees without state")
    void submitBundleSkipsGasExtraWithoutFeeContext() {
        final var feeResult = new FeeResult();

        subject.accumulateServiceFee(
                submitBundleOfBytes(50),
                mock(SimpleFeeContext.class),
                feeResult,
                scheduleWithExtras(Extra.CLPR_BUNDLE_BYTES, Extra.GAS));

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE + 50 * BYTE_FEE);
    }

    @Test
    @DisplayName("ClprCompleteChannel adds the verifier-gas extra but never the bundle-byte extra")
    void completeChannelAddsOnlyVerifierGasExtra() {
        final var calculator =
                new ClprFeeCalculator(CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.CLPR_BUNDLE_BYTES, BYTE_FEE), makeExtraDef(Extra.GAS, GAS_FEE))
                .services(makeService(
                        "ClprService",
                        makeServiceFee(
                                CLPR_COMPLETE_CHANNEL,
                                SUBMIT_BUNDLE_BASE_FEE,
                                makeExtraIncluded(Extra.CLPR_BUNDLE_BYTES, 0),
                                makeExtraIncluded(Extra.GAS, 0))))
                .build();

        calculator.accumulateServiceFee(
                TransactionBody.DEFAULT, simpleFeeContextWithVerifierGasLimit(1_000L), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE + 1_000 * GAS_FEE);
    }

    @Test
    @DisplayName("ClprCompleteChannel charges state bytes for its proofs beyond the included allowance")
    void completeChannelChargesStateBytesForProofs() {
        final var calculator =
                new ClprFeeCalculator(CLPR_COMPLETE_CHANNEL, TransactionBody.DataOneOfType.CLPR_COMPLETE_CHANNEL);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.STATE_BYTES, BYTE_FEE))
                .services(makeService(
                        "ClprService",
                        makeServiceFee(
                                CLPR_COMPLETE_CHANNEL,
                                SUBMIT_BUNDLE_BASE_FEE,
                                makeExtraIncluded(Extra.STATE_BYTES, 100))))
                .build();
        final var body = TransactionBody.newBuilder()
                .clprCompleteChannel(ClprCompleteChannelTransactionBody.newBuilder()
                        .configProofBytes(Bytes.wrap(new byte[120]))
                        .endpointManifestProofBytes(Bytes.wrap(new byte[30]))
                        .build())
                .build();

        calculator.accumulateServiceFee(body, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        // 150 proof bytes, 100 included
        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE + 50 * BYTE_FEE);
    }

    @Test
    @DisplayName("Other CLPR transactions ignore extras listed for them")
    void otherOpsIgnoreExtras() {
        final var calculator =
                new ClprFeeCalculator(CLPR_REGISTER_CHANNEL, TransactionBody.DataOneOfType.CLPR_REGISTER_CHANNEL);
        final var feeResult = new FeeResult();
        final var feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.CLPR_BUNDLE_BYTES, BYTE_FEE))
                .services(makeService(
                        "ClprService",
                        makeServiceFee(
                                CLPR_REGISTER_CHANNEL,
                                SUBMIT_BUNDLE_BASE_FEE,
                                makeExtraIncluded(Extra.CLPR_BUNDLE_BYTES, 0))))
                .build();

        calculator.accumulateServiceFee(TransactionBody.DEFAULT, mock(SimpleFeeContext.class), feeResult, feeSchedule);

        assertThat(feeResult.getServiceTotalTinycents()).isEqualTo(SUBMIT_BUNDLE_BASE_FEE);
    }

    private static FeeSchedule scheduleWithExtras(final Extra... extras) {
        final var included =
                Stream.of(extras).map(extra -> makeExtraIncluded(extra, 0)).toArray(ExtraFeeReference[]::new);
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.CLPR_BUNDLE_BYTES, BYTE_FEE), makeExtraDef(Extra.GAS, GAS_FEE))
                .services(makeService(
                        "ClprService", makeServiceFee(CLPR_SUBMIT_BUNDLE, SUBMIT_BUNDLE_BASE_FEE, included)))
                .build();
    }

    private static SimpleFeeContext simpleFeeContextWithVerifierGasLimit(final long verifierGasLimit) {
        final var simpleFeeContext = mock(SimpleFeeContext.class);
        given(simpleFeeContext.feeContext()).willReturn(mock(FeeContext.class));
        given(simpleFeeContext.configuration())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("clpr.verifierGasLimit", verifierGasLimit)
                        .getOrCreateConfig());
        return simpleFeeContext;
    }

    private static TransactionBody submitBundleOfBytes(final int numBytes) {
        return TransactionBody.newBuilder()
                .clprSubmitBundle(ClprSubmitBundleTransactionBody.newBuilder()
                        .bundlePayload(Bytes.wrap(new byte[numBytes]))
                        .build())
                .build();
    }

    /** All 8 CLPR transaction functionalities paired with their TransactionBody discriminants. */
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
                Arguments.of(CLPR_SUBMIT_BUNDLE, TransactionBody.DataOneOfType.CLPR_SUBMIT_BUNDLE));
    }
}
