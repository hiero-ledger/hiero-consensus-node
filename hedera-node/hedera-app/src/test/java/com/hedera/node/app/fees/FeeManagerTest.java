// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FEE_FILE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.ExtraFeeDefinition;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeeManagerTest {

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private ServiceFeeCalculator topicCreateCalculator;

    @Mock
    private ServiceFeeCalculator cryptoTransferCalculator;

    @Mock
    private QueryFeeCalculator fileGetInfoCalculator;

    @Mock
    private ServiceFeeCalculator hookDispatchCalculator;

    @Mock
    private ServiceFeeCalculator unsetTypeCalculator;

    @Mock
    private QueryFeeCalculator unsetQueryCalculator;

    private FeeManager subject;

    @BeforeEach
    void setUp() {
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of());
    }

    @Test
    void updateSimpleFeesParsesFeeSchedule() {
        final var validSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.KEYS, 1),
                        makeExtraDef(Extra.STATE_BYTES, 1),
                        makeExtraDef(Extra.SIGNATURES, 1),
                        makeExtraDef(Extra.TOKEN_ASSOCIATE, 1),
                        makeExtraDef(Extra.AIRDROPS, 1),
                        makeExtraDef(Extra.GAS, 852))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 100, makeExtraIncluded(Extra.KEYS, 1))))
                .build();
        final var bytes = org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(validSchedule);

        final var result = subject.updateSimpleFees(bytes);

        assertEquals(SUCCESS, result);
    }

    @Test
    void getGasPriceInTinyCentsUsesSimpleFeesWhenGasExtraPresent() {
        final var simpleSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.GAS, 100_000L),
                        makeExtraDef(Extra.TOKEN_ASSOCIATE, 1),
                        makeExtraDef(Extra.AIRDROPS, 1))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 0)))
                .build();
        subject.updateSimpleFees(org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(simpleSchedule));

        assertEquals(100_000L, subject.getGasPriceInTinyCents(Instant.now()));
    }

    @Test
    void updateSimpleFeesRejectsScheduleWithoutGasExtra() {
        final var simpleSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1_000L))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(CRYPTO_CREATE, 0)))
                .build();

        final var result =
                subject.updateSimpleFees(org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.toBytes(simpleSchedule));

        assertEquals(FEE_SCHEDULE_FILE_PART_UPLOADED, result);
    }

    @Test
    void getGasPriceInTinyCentsThrowsWhenSimpleFeesNotLoaded() {
        assertThrows(IllegalStateException.class, () -> subject.getGasPriceInTinyCents(Instant.ofEpochSecond(1L)));
    }

    @Test
    void updateSimpleFeesRequiringFullCoverageRejectsScheduleThatDoesNotPriceRegisteredTransactionType() {
        given(topicCreateCalculator.getTransactionType())
                .willReturn(TransactionBody.DataOneOfType.CONSENSUS_CREATE_TOPIC);
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(topicCreateCalculator), Set.of());
        final var complete =
                scheduleWith(makeServiceFee(CRYPTO_CREATE, 100), makeServiceFee(CONSENSUS_CREATE_TOPIC, 100));
        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(complete), true));

        final var incomplete = scheduleWith(makeServiceFee(CRYPTO_CREATE, 100));

        assertEquals(INVALID_FEE_FILE, subject.updateSimpleFees(toBytes(incomplete), true));
        assertEquals(complete, subject.getSimpleFeesSchedule());
    }

    @Test
    void updateSimpleFeesRequiringFullCoverageRejectsScheduleThatDoesNotPriceRegisteredQueryType() {
        given(fileGetInfoCalculator.getQueryType()).willReturn(Query.QueryOneOfType.FILE_GET_INFO);
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of(fileGetInfoCalculator));
        final var complete = scheduleWith(makeServiceFee(CRYPTO_CREATE, 100), makeServiceFee(FILE_GET_INFO, 100));
        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(complete), true));

        final var incomplete = scheduleWith(makeServiceFee(CRYPTO_CREATE, 100));

        assertEquals(INVALID_FEE_FILE, subject.updateSimpleFees(toBytes(incomplete), true));
        assertEquals(complete, subject.getSimpleFeesSchedule());
    }

    @Test
    void updateSimpleFeesDoesNotRequirePriceForHandlerStepFunction() {
        given(hookDispatchCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.HOOK_DISPATCH);
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(hookDispatchCalculator), Set.of());
        final var schedule = scheduleWith(makeServiceFee(CRYPTO_CREATE, 100));

        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(schedule), true));
    }

    @Test
    void updateSimpleFeesInstallsScheduleThatLeavesRegisteredTypeUnpriced() {
        given(topicCreateCalculator.getTransactionType())
                .willReturn(TransactionBody.DataOneOfType.CONSENSUS_CREATE_TOPIC);
        given(fileGetInfoCalculator.getQueryType()).willReturn(Query.QueryOneOfType.FILE_GET_INFO);
        subject = new FeeManager(
                exchangeRateManager,
                congestionMultipliers,
                Set.of(topicCreateCalculator),
                Set.of(fileGetInfoCalculator));
        final var incomplete = scheduleWith(makeServiceFee(CRYPTO_CREATE, 100));

        // Same result as a restart that reads this schedule from state, so all nodes keep the same schedule
        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(incomplete)));
        assertEquals(incomplete, subject.getSimpleFeesSchedule());
    }

    @Test
    void updateSimpleFeesRejectsScheduleMissingAnExtraReferenceACalculatorNeeds() {
        given(cryptoTransferCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.CRYPTO_TRANSFER);
        given(cryptoTransferCalculator.requiredExtras()).willReturn(Set.of(Extra.ACCOUNTS));
        subject =
                new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(cryptoTransferCalculator), Set.of());
        final var complete = scheduleWith(
                extraDefs(Extra.ACCOUNTS, Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS),
                makeServiceFee(CRYPTO_TRANSFER, 100, makeExtraIncluded(Extra.ACCOUNTS, 2)));
        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(complete), true));

        final var withoutReference = scheduleWith(
                extraDefs(Extra.ACCOUNTS, Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS), makeServiceFee(CRYPTO_TRANSFER, 100));

        assertEquals(INVALID_FEE_FILE, subject.updateSimpleFees(toBytes(withoutReference), true));
        assertEquals(complete, subject.getSimpleFeesSchedule());
    }

    @Test
    void updateSimpleFeesRejectsScheduleMissingAnExtraHandlersReadDirectly() {
        final var complete =
                scheduleWith(extraDefs(Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS), makeServiceFee(CRYPTO_CREATE, 100));
        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(complete), true));

        final var withoutAirdrops = scheduleWith(extraDefs(Extra.TOKEN_ASSOCIATE), makeServiceFee(CRYPTO_CREATE, 100));
        final var withoutAssociate = scheduleWith(extraDefs(Extra.AIRDROPS), makeServiceFee(CRYPTO_CREATE, 100));

        assertEquals(INVALID_FEE_FILE, subject.updateSimpleFees(toBytes(withoutAirdrops), true));
        assertEquals(INVALID_FEE_FILE, subject.updateSimpleFees(toBytes(withoutAssociate), true));
        assertEquals(complete, subject.getSimpleFeesSchedule());
    }

    @Test
    void missingExtrasNamesEachGap() {
        given(cryptoTransferCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.CRYPTO_TRANSFER);
        given(cryptoTransferCalculator.requiredExtras()).willReturn(Set.of(Extra.ACCOUNTS, Extra.KEYS));
        subject =
                new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(cryptoTransferCalculator), Set.of());
        final var schedule = scheduleWith(
                extraDefs(Extra.ACCOUNTS, Extra.KEYS, Extra.TOKEN_ASSOCIATE),
                makeServiceFee(CRYPTO_TRANSFER, 100, makeExtraIncluded(Extra.ACCOUNTS, 2)));

        assertEquals(Set.of("AIRDROPS", "CRYPTO_TRANSFER.KEYS"), subject.missingExtras(schedule));
    }

    @Test
    void missingExtrasLeavesUnpricedFunctionsToTheCoverageCheck() {
        given(cryptoTransferCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.CRYPTO_TRANSFER);
        // Non-empty on purpose: if the unpriced-function skip were broken and this requirement were checked
        // anyway, the empty result below would instead report CRYPTO_TRANSFER.ACCOUNTS as missing.
        given(cryptoTransferCalculator.requiredExtras()).willReturn(Set.of(Extra.ACCOUNTS));
        subject =
                new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(cryptoTransferCalculator), Set.of());
        final var unpriced =
                scheduleWith(extraDefs(Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS), makeServiceFee(CRYPTO_CREATE, 100));

        assertEquals(Set.of(), subject.missingExtras(unpriced));

        // Control: the same requirement does surface once CRYPTO_TRANSFER is priced but still lacks the
        // reference, proving the empty result above comes from the unpriced-function skip, not from the
        // requirement being empty.
        final var pricedButIncomplete =
                scheduleWith(extraDefs(Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS), makeServiceFee(CRYPTO_TRANSFER, 100));

        assertEquals(Set.of("CRYPTO_TRANSFER.ACCOUNTS"), subject.missingExtras(pricedButIncomplete));
    }

    @Test
    void updateSimpleFeesCanInstallScheduleMissingExtrasWhenCoverageIsNotRequired() {
        given(cryptoTransferCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.CRYPTO_TRANSFER);
        given(cryptoTransferCalculator.requiredExtras()).willReturn(Set.of(Extra.ACCOUNTS));
        subject =
                new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(cryptoTransferCalculator), Set.of());
        // Missing the ACCOUNTS reference, so missingExtras(incomplete) is non-empty: this exercises the lenient
        // branch actually being taken, not merely a schedule the strict path would have accepted anyway.
        final var incomplete = scheduleWith(makeServiceFee(CRYPTO_TRANSFER, 100));

        assertEquals(SUCCESS, subject.updateSimpleFees(toBytes(incomplete), false));
        assertEquals(incomplete, subject.getSimpleFeesSchedule());
    }

    @Test
    void updateSimpleFeesRejectsUnparseableContentWithTheChunkStatusRegardlessOfRequireFullCoverage() {
        final var garbage = Bytes.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        assertEquals(FEE_SCHEDULE_FILE_PART_UPLOADED, subject.updateSimpleFees(garbage, true));
        assertEquals(FEE_SCHEDULE_FILE_PART_UPLOADED, subject.updateSimpleFees(garbage, false));
    }

    @Test
    void updateSimpleFeesRejectsAStructurallyIncompleteScheduleWithTheChunkStatusEvenWhenStrict() {
        // Empty content parses to FeeSchedule.DEFAULT, which fails isValid (no node/network fee): this is the
        // benign "still uploading" bucket, distinct from a complete schedule the calculators cannot evaluate.
        assertEquals(FEE_SCHEDULE_FILE_PART_UPLOADED, subject.updateSimpleFees(Bytes.EMPTY, true));
    }

    @Test
    void updateSimpleFeesFailsForCalculatorWithUnsetTransactionType() {
        given(unsetTypeCalculator.getTransactionType()).willReturn(TransactionBody.DataOneOfType.UNSET);
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(unsetTypeCalculator), Set.of());
        final var bytes = toBytes(scheduleWith(makeServiceFee(CRYPTO_CREATE, 100)));

        assertThrows(IllegalStateException.class, () -> subject.updateSimpleFees(bytes));
    }

    @Test
    void updateSimpleFeesFailsForCalculatorWithUnsetQueryType() {
        given(unsetQueryCalculator.getQueryType()).willReturn(Query.QueryOneOfType.UNSET);
        subject = new FeeManager(exchangeRateManager, congestionMultipliers, Set.of(), Set.of(unsetQueryCalculator));
        final var bytes = toBytes(scheduleWith(makeServiceFee(CRYPTO_CREATE, 100)));

        assertThrows(IllegalStateException.class, () -> subject.updateSimpleFees(bytes));
    }

    private static FeeSchedule scheduleWith(final ServiceFeeDefinition... fees) {
        return scheduleWith(extraDefs(Extra.TOKEN_ASSOCIATE, Extra.AIRDROPS), fees);
    }

    private static ExtraFeeDefinition[] extraDefs(final Extra... extras) {
        return Arrays.stream(extras).map(extra -> makeExtraDef(extra, 1)).toArray(ExtraFeeDefinition[]::new);
    }

    private static FeeSchedule scheduleWith(final ExtraFeeDefinition[] extras, final ServiceFeeDefinition... fees) {
        final var allExtras = new ArrayList<>(List.of(makeExtraDef(Extra.GAS, 852)));
        allExtras.addAll(List.of(extras));
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(allExtras)
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(0).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Test", fees))
                .build();
    }

    private static Bytes toBytes(final FeeSchedule schedule) {
        return FeeSchedule.PROTOBUF.toBytes(schedule);
    }
}
