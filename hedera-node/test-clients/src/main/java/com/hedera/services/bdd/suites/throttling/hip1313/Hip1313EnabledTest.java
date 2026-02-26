// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.allVisibleItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithChild;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.ALL_TX_IDS;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.interpolatePiecewiseLinear;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.linearInterpolate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

@Tag(SIMPLE_FEES)
@Tag(MATS)
@HapiTestLifecycle
@Isolated
public class Hip1313EnabledTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double CRYPTO_CREATE_BASE_FEE = 0.05;
    private static final int CRYPTO_CREATE_HV_TPS = 800;
    private static final int LINEAR_CRYPTO_CREATE_MAX_MULTIPLIER = 200_000;
    public static final NavigableMap<Integer, Long> CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP = new TreeMap<>(Map.ofEntries(
            Map.entry(2, 4000L),
            Map.entry(3, 8000L),
            Map.entry(5, 10000L),
            Map.entry(7, 15000L),
            Map.entry(10, 20000L),
            Map.entry(15, 30000L),
            Map.entry(20, 40000L),
            Map.entry(50, 60000L),
            Map.entry(100, 80000L),
            Map.entry(200, 100000L),
            Map.entry(500, 150000L),
            Map.entry(1000, 200000L),
            Map.entry(10000, 200000L)));
    public static final NavigableMap<Integer, Long> SCHEDULE_CREATE_MULTIPLIER_MAP = new TreeMap<>(Map.ofEntries(
            Map.entry(100, 4000L),
            Map.entry(150, 8000L),
            Map.entry(250, 10000L),
            Map.entry(350, 15000L),
            Map.entry(500, 20000L),
            Map.entry(750, 30000L),
            Map.entry(1000, 40000L),
            Map.entry(2500, 60000L),
            Map.entry(5000, 80000L),
            Map.entry(10000, 100000L)));

    private static final double SCHEDULE_CREATE_BASE_FEE = 0.01;
    private static final int SCHEDULE_CREATE_HV_TPS = 1300;
    private static final int TOPIC_CREATE_HV_TPS = 800;
    private static final double TOPIC_CREATE_BASE_FEE = 0.01;
    private static final double MULTIPLIER_TOLERANCE = 0.05;
    private static final long ONE_X_MULTIPLIER = 1000L;
    private static final long FOUR_X_MULTIPLIER = 4000L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "networkAdmin.highVolumeThrottlesEnabled", "true"));
        testLifecycle.doAdhoc(cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS));
    }

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/hip1313-high-volume-total-throttle.json")
    final Stream<DynamicTest> totalHighVolumeThrottleAppliesAcrossDifferentFunctionalities() {
        return hapiTest(
                cryptoCreate("hvTotalCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(OK)
                        .via("createAccount"),
                getTxnRecord("createAccount").logged(),
                createTopic("createAccount")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(BUSY));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAutoCreationsUsesHighVolume() {
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("hvTotalCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(OK)
                        .via("createAccount"),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "alias"))
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("autoCreation"),
                getTxnRecord("autoCreation")
                        .andAllChildRecords()
                        .exposingAllTo(records -> {
                            assertAnyRecordMatches(
                                    records,
                                    record -> record.getTransactionID().getNonce() > 0
                                            && record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
                            assertNoRecordMatches(
                                    records,
                                    record -> record.getTransactionID().getNonce() == 0
                                            && record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
                        })
                        .logged(),
                // Apply high volume multiplier for crypto create only
                validateChargedUsdWithChild("autoCreation", 0.0001 + (0.05 * 4), 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferAutoCreationWithoutHighVolumeUsesDefaultPricing() {
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("nonHvCreate").payingWith(CIVILIAN_PAYER).via("createAccount"),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "alias"))
                        .payingWith(CIVILIAN_PAYER)
                        .via("autoCreationNoHv"),
                getTxnRecord("autoCreationNoHv")
                        .andAllChildRecords()
                        .exposingAllTo(records ->
                                assertNoRecordMatches(records, record -> record.getHighVolumePricingMultiplier() > 0L))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> airdropAutoCreationsUsesHighVolume() {
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("hvTotalCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(OK)
                        .via("createAccount"),
                tokenCreate("token").treasury(CIVILIAN_PAYER).initialSupply(1000L),
                tokenAirdrop(TokenMovement.moving(10, "token").between(CIVILIAN_PAYER, "alias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("autoCreation"),
                getAutoCreatedAccountBalance("alias").hasTokenBalance("token", 10),
                getTxnRecord("autoCreation")
                        .andAllChildRecords()
                        .exposingAllTo(records -> {
                            assertAnyRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() == FOUR_X_MULTIPLIER);
                            assertNoRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() > FOUR_X_MULTIPLIER);
                        })
                        .logged(),
                validateChargedUsdWithChild("autoCreation", (0.05 * 4) + (0.001 * 4) + (0.1 * 4), 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> airdropAutoCreationWithoutHighVolumeUsesDefaultPricing() {
        return hapiTest(
                newKeyNamed("alias"),
                cryptoCreate("nonHvCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .hasPrecheck(OK)
                        .via("createAccount"),
                tokenCreate("token").treasury(CIVILIAN_PAYER).initialSupply(1000L),
                tokenAirdrop(TokenMovement.moving(10, "token").between(CIVILIAN_PAYER, "alias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .via("airdropNoHv"),
                getTxnRecord("airdropNoHv")
                        .andAllChildRecords()
                        .exposingAllTo(records ->
                                assertNoRecordMatches(records, record -> record.getHighVolumePricingMultiplier() > 0L))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> claimAirdropHollowCompletionUsesHighVolume() {
        final var hollowReceiver = "hollowReceiver";
        return hapiTest(
                createHollow(1, i -> hollowReceiver),
                tokenCreate("token").treasury(CIVILIAN_PAYER).initialSupply(1000L),
                cryptoUpdate(hollowReceiver)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowReceiver))
                        .maxAutomaticAssociations(0),
                tokenAirdrop(TokenMovement.moving(10, "token").between(CIVILIAN_PAYER, hollowReceiver))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .via("pendingAirdrop"),
                getAutoCreatedAccountBalance(hollowReceiver).hasTokenBalance("token", 0),
                tokenClaimAirdrop(pendingAirdrop(CIVILIAN_PAYER, hollowReceiver, "token"))
                        .payingWith(hollowReceiver)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowReceiver))
                        .withHighVolume()
                        .via("claimAirdrop"),
                getAutoCreatedAccountBalance(hollowReceiver).hasTokenBalance("token", 10),
                getTxnRecord("claimAirdrop")
                        .andAllChildRecords()
                        .exposingAllTo(records -> assertAnyRecordMatches(
                                records, record -> record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER))
                        .logged(),
                validateChargedUsdWithChild("claimAirdrop", 0.001 * 4, 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> claimAirdropHollowCompletionWithoutHighVolumeUsesDefaultPricing() {
        final var hollowReceiver = "hollowReceiverNoHv";
        return hapiTest(
                createHollow(1, i -> hollowReceiver),
                tokenCreate("tokenNoHv").treasury(CIVILIAN_PAYER).initialSupply(1000L),
                cryptoUpdate(hollowReceiver)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowReceiver))
                        .maxAutomaticAssociations(0),
                tokenAirdrop(TokenMovement.moving(10, "tokenNoHv").between(CIVILIAN_PAYER, hollowReceiver))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .via("pendingAirdropNoHv"),
                getAutoCreatedAccountBalance(hollowReceiver).hasTokenBalance("tokenNoHv", 0),
                tokenClaimAirdrop(pendingAirdrop(CIVILIAN_PAYER, hollowReceiver, "tokenNoHv"))
                        .payingWith(hollowReceiver)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowReceiver))
                        .via("claimAirdropNoHv"),
                getAutoCreatedAccountBalance(hollowReceiver).hasTokenBalance("tokenNoHv", 10),
                getTxnRecord("claimAirdropNoHv")
                        .andAllChildRecords()
                        .exposingAllTo(records ->
                                assertNoRecordMatches(records, record -> record.getHighVolumePricingMultiplier() > 0L))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> highVolumeFlagOnUnsupportedTxnIsIgnored() {
        return hapiTest(
                cryptoUpdate(CIVILIAN_PAYER)
                        .memo("hip-1313-ignore")
                        .withHighVolume()
                        .via("highVolumeUpdate"),
                getTxnRecord("highVolumeUpdate")
                        .andAllChildRecords()
                        .exposingAllTo(records ->
                                assertNoRecordMatches(records, record -> record.getHighVolumePricingMultiplier() > 0L))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> cryptoTransferWithoutAutoCreationDoesNotApplyHighVolumePricing() {
        return hapiTest(
                cryptoCreate("existingReceiver").balance(ONE_HBAR),
                cryptoTransfer(TokenMovement.movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "existingReceiver"))
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("plainTransfer"),
                getTxnRecord("plainTransfer")
                        .andAllChildRecords()
                        .exposingAllTo(records -> {
                            assertNoRecordMatches(records, record -> record.getHighVolumePricingMultiplier() > 0L);
                            assertNoRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
                        })
                        .logged(),
                validateChargedUsdWithChild("plainTransfer", 0.0001, 0.01));
    }

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/hip1313-no-hv-one-tps-create.json")
    final Stream<DynamicTest> highVolumeTxnFallsBackToNormalThrottleWhenNoHighVolumeBucketExists() {
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-no-hv-one-tps-create.json"),
                cryptoCreate("fallbackThrottleA")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .deferStatusResolution()
                        .hasPrecheck(OK),
                cryptoCreate("fallbackThrottleB")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(BUSY));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> highVolumeTxnsWorkAsExpectedForCryptoCreate() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                recordStreamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overridingTwo("fees.simpleFeesEnabled", "true", "networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> submitHighVolumeCryptoCreates(spec, 200)),
                // ensure one record is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    final var entries = filteredHighVolumeEntries(highVolumeTxns, e -> true);
                    final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(CRYPTO_CREATE_HV_TPS, 1000);
                    var numCreateTxnsAllowed = 0;
                    for (final var entry : entries) {
                        throttle.leakUntil(entry.consensusTime());
                        final var utilizationBasisPointsBefore = throttle.instantaneousBps();
                        throttle.allow(1, entry.consensusTime());
                        numCreateTxnsAllowed++;
                        final var utilizationBasisPointsAfter = throttle.instantaneousBps();
                        assertHighVolumeMultiplierSet(entry, "crypto create");
                        final var fee = entry.txnRecord().getTransactionFee();
                        final var observedMultiplier = observedMultiplier(spec, fee, CRYPTO_CREATE_BASE_FEE);
                        assertMultiplierAtLeast(observedMultiplier, "crypto create");
                        assertMultiplierMatchesExpectation(
                                CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP,
                                observedMultiplier,
                                utilizationBasisPointsBefore,
                                utilizationBasisPointsAfter,
                                "crypto create",
                                numCreateTxnsAllowed);
                    }
                    assertEquals(200, entries.size());
                }));
    }

    @GenesisHapiTest
    @Disabled
    final Stream<DynamicTest> mixedHighVolumeTxnsWorkAsExpectedForTopicCreateAndScheduleCreate() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        final int numBursts = 200;
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-multi-op-pricing-throttles.json"),
                recordStreamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overridingTwo("fees.simpleFeesEnabled", "true", "networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> submitMixedHighVolumeTopicAndScheduleCreates(spec, numBursts)),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    final var entries = filteredHighVolumeEntries(
                            highVolumeTxns,
                            e -> e.body().hasConsensusCreateTopic() || e.body().hasScheduleCreate());
                    final var topicThrottle = DeterministicThrottle.withTpsAndBurstPeriodMs(TOPIC_CREATE_HV_TPS, 1000);
                    final var scheduleThrottle =
                            DeterministicThrottle.withTpsAndBurstPeriodMs(SCHEDULE_CREATE_HV_TPS, 1000);
                    int topicCreates = 0;
                    int scheduleCreates = 0;
                    for (final var entry : entries) {
                        final var fee = entry.txnRecord().getTransactionFee();
                        if (entry.body().hasConsensusCreateTopic()) {
                            topicThrottle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = topicThrottle.instantaneousBps();
                            topicThrottle.allow(1, entry.consensusTime());
                            topicCreates++;
                            final var utilizationBasisPointsAfter = topicThrottle.instantaneousBps();
                            assertHighVolumeMultiplierSet(entry, "topic create");
                            final var observedMultiplier = observedMultiplier(spec, fee, TOPIC_CREATE_BASE_FEE);
                            final var observedRawMultiplier =
                                    entry.txnRecord().getHighVolumePricingMultiplier() / 1000.0;
                            assertMultiplierAtLeast(observedMultiplier, "topic create");
                            assertMultiplierMatchesExpectation(
                                    CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP,
                                    observedRawMultiplier,
                                    utilizationBasisPointsBefore,
                                    utilizationBasisPointsAfter,
                                    "topic create",
                                    topicCreates);
                        } else if (entry.body().hasScheduleCreate()) {
                            scheduleThrottle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = scheduleThrottle.instantaneousBps();
                            scheduleThrottle.allow(1, entry.consensusTime());
                            scheduleCreates++;
                            final var utilizationBasisPointsAfter = scheduleThrottle.instantaneousBps();
                            assertHighVolumeMultiplierSet(entry, "schedule create");
                            final var observedRawMultiplier =
                                    entry.txnRecord().getHighVolumePricingMultiplier() / 1000.0;
                            assertMultiplierMatchesExpectation(
                                    SCHEDULE_CREATE_MULTIPLIER_MAP,
                                    observedRawMultiplier,
                                    utilizationBasisPointsBefore,
                                    utilizationBasisPointsAfter,
                                    "schedule create",
                                    scheduleCreates);
                        }
                    }
                    assertEquals(numBursts * 2, entries.size());
                    assertEquals(numBursts, topicCreates);
                    assertEquals(numBursts, scheduleCreates);
                }));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> cryptoCreateUsesLinearInterpolationWhenPricingCurveMissing() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                recordStreamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overridingTwo("fees.simpleFeesEnabled", "true", "networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            getFileContents(SIMPLE_FEE_SCHEDULE)
                                    .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
                    allRunFor(
                            spec,
                            updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, simpleFeesWithoutCryptoCreatePricingCurve()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                }),
                withOpContext((spec, opLog) -> submitHighVolumeCryptoCreates(spec, 200)),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    try {
                        final var entries = filteredHighVolumeEntries(
                                highVolumeTxns, e -> e.body().hasCryptoCreateAccount());
                        final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(CRYPTO_CREATE_HV_TPS, 1000);
                        for (final var entry : entries) {
                            throttle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = throttle.instantaneousBps();
                            throttle.allow(1, entry.consensusTime());
                            final long expectedRawMultiplier = linearInterpolate(
                                    0,
                                    1000L,
                                    10_000,
                                    LINEAR_CRYPTO_CREATE_MAX_MULTIPLIER,
                                    utilizationBasisPointsBefore);
                            final long expectedMultiplier = Math.max(1000L, expectedRawMultiplier);
                            // Proto default is 0 when field is not present; treat this as the default multiplier 1x.
                            final var actualMultiplier =
                                    Math.max(1000L, entry.txnRecord().getHighVolumePricingMultiplier());
                            assertEquals(
                                    expectedMultiplier,
                                    actualMultiplier,
                                    "Given BPS of " + utilizationBasisPointsBefore
                                            + ", expected linear interpolated multiplier " + expectedMultiplier
                                            + " but found " + actualMultiplier);
                        }
                        assertEquals(200, entries.size());
                    } finally {
                        final var snapshot = originalSimpleFeeSchedule.get();
                        if (snapshot != null) {
                            allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, snapshot));
                            assertTrue(
                                    spec.tryReinitializingFees(),
                                    "Failed to reinitialize fees after restoring simple fee schedule");
                        }
                    }
                }));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> cryptoCreateWithHighVolumeUsesDefaultMultiplierWhenMaxIsOneX() {
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overridingTwo("fees.simpleFeesEnabled", "true", "networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            getFileContents(SIMPLE_FEE_SCHEDULE)
                                    .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
                    allRunFor(
                            spec,
                            updateLargeFile(
                                    GENESIS, SIMPLE_FEE_SCHEDULE, simpleFeesWithOneXCryptoCreateHighVolumeRates()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                }),
                cryptoCreate("defaultMultiplierCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("defaultMultiplierCreateTxn"),
                getTxnRecord("defaultMultiplierCreateTxn")
                        .andAllChildRecords()
                        .exposingAllTo(records -> {
                            assertAnyRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() == ONE_X_MULTIPLIER);
                            assertNoRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
                        })
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var snapshot = originalSimpleFeeSchedule.get();
                    if (snapshot != null) {
                        allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, snapshot));
                        assertTrue(
                                spec.tryReinitializingFees(),
                                "Failed to reinitialize fees after restoring simple fee schedule");
                    }
                }));
    }

    @HapiTest
    final Stream<DynamicTest> onlyCryptoCreateChildHasHigherFeesWhileTransferStaysBaseFee() {
        return hapiTest(
                newKeyNamed("aliasFee"),
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "aliasFee"))
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("feeSplitTransfer"),
                getTxnRecord("feeSplitTransfer")
                        .andAllChildRecords()
                        .exposingAllTo(Hip1313EnabledTest::assertOnlyChildHasBoostedHighVolumeMultiplier),
                validateChargedUsdWithChild("feeSplitTransfer", 0.0001 + (0.05 * 4), 0.01));
    }

    private static void assertOnlyChildHasBoostedHighVolumeMultiplier(@NonNull final List<TransactionRecord> records) {
        assertNoRecordMatches(
                records,
                record -> record.getTransactionID().getNonce() == 0
                        && record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
        assertAnyRecordMatches(
                records,
                record -> record.getTransactionID().getNonce() > 0
                        && record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
    }

    public static long getInterpolatedMultiplier(
            final NavigableMap<Integer, Long> map, final int utilizationBasisPoints) {
        return interpolatePiecewiseLinear(asPiecewiseLinearCurve(map), utilizationBasisPoints);
    }

    private static PiecewiseLinearCurve asPiecewiseLinearCurve(final NavigableMap<Integer, Long> map) {
        final var points = map.entrySet().stream()
                .map(entry -> PiecewiseLinearPoint.newBuilder()
                        .utilizationBasisPoints(entry.getKey())
                        .multiplier(entry.getValue().intValue())
                        .build())
                .toList();
        return PiecewiseLinearCurve.newBuilder().points(points).build();
    }

    private static void submitHighVolumeCryptoCreates(@NonNull final HapiSpec spec, final int numCreates) {
        for (int i = 0; i < numCreates; i++) {
            allRunFor(
                    spec,
                    cryptoCreate("hvTotalCreate" + i)
                            .payingWith(CIVILIAN_PAYER)
                            .deferStatusResolution()
                            .withHighVolume());
        }
    }

    private static void submitMixedHighVolumeTopicAndScheduleCreates(
            @NonNull final HapiSpec spec, final int numBursts) {
        for (int i = 0; i < numBursts; i++) {
            allRunFor(
                    spec,
                    createTopic("mixedHvTopic" + i)
                            .payingWith(CIVILIAN_PAYER)
                            .deferStatusResolution()
                            .withHighVolume(),
                    scheduleCreate("mixedHvSchedule" + i, cryptoCreate("mixedHvScheduledAccount" + i))
                            .payingWith(CIVILIAN_PAYER)
                            .expiringIn(7_200L + (i * 1_000L))
                            .deferStatusResolution()
                            .withHighVolume());
        }
    }

    private static List<RecordStreamEntry> filteredHighVolumeEntries(
            @NonNull final AtomicReference<List<RecordStreamEntry>> highVolumeTxns,
            @NonNull final Predicate<RecordStreamEntry> additionalFilter) {
        return highVolumeTxns.get().stream()
                .filter(e -> e.body().getHighVolume())
                .filter(additionalFilter)
                // Expected multipliers are derived from utilization progression. For entries that can
                // share the same consensus timestamp, add txn-id tie-breakers for deterministic ordering.
                .sorted(Comparator.comparing(RecordStreamEntry::consensusTime)
                        .thenComparingLong(
                                e -> e.txnId().getTransactionValidStart().getSeconds())
                        .thenComparingInt(
                                e -> e.txnId().getTransactionValidStart().getNanos())
                        .thenComparingInt(e -> e.txnId().getNonce())
                        .thenComparingLong(e -> e.txnId().getAccountID().getAccountNum()))
                .toList();
    }

    private static double observedMultiplier(
            @NonNull final HapiSpec spec, final long feeInTinybars, final double baseFeeUsd) {
        return spec.ratesProvider().toUsdWithActiveRates(feeInTinybars) / baseFeeUsd;
    }

    private static void assertMultiplierAtLeast(final double observedMultiplier, @NonNull final String operation) {
        assertTrue(
                observedMultiplier >= 4,
                "Observed " + operation + " multiplier should be >= 4, but was " + observedMultiplier);
    }

    private static void assertHighVolumeMultiplierSet(
            @NonNull final RecordStreamEntry entry, @NonNull final String operation) {
        final var multiplier = entry.txnRecord().getHighVolumePricingMultiplier();
        assertTrue(
                multiplier >= FOUR_X_MULTIPLIER,
                "Expected " + operation + " high-volume multiplier to be set (>4), but was " + multiplier);
    }

    private static void assertAnyRecordMatches(
            @NonNull final List<TransactionRecord> records, @NonNull final Predicate<TransactionRecord> predicate) {
        final var conditionMatched = records.stream().anyMatch(predicate);
        assertTrue(conditionMatched);
    }

    private static void assertNoRecordMatches(
            @NonNull final List<TransactionRecord> records, @NonNull final Predicate<TransactionRecord> predicate) {
        final var conditionMatched = records.stream().anyMatch(predicate);
        assertFalse(conditionMatched);
    }

    private static void assertMultiplierMatchesExpectation(
            @NonNull final NavigableMap<Integer, Long> multiplierMap,
            final double observedMultiplier,
            final int utilizationBasisPointsBefore,
            final int utilizationBasisPointsAfter,
            @NonNull final String operation,
            final int numTxnsAllowed) {
        final var minBps = Math.max(0, Math.min(utilizationBasisPointsBefore, utilizationBasisPointsAfter) - 1);
        final var maxBps = Math.min(10_000, Math.max(utilizationBasisPointsBefore, utilizationBasisPointsAfter) + 1);
        final var acceptableMultipliers = IntStream.rangeClosed(minBps, maxBps)
                .mapToDouble(bps -> getInterpolatedMultiplier(multiplierMap, bps) / 1000.0)
                .distinct()
                .toArray();
        System.out.println("OBSERVED " + observedMultiplier + " EXPECTED: " + Arrays.toString(acceptableMultipliers)
                + " for " + operation
                + " BPS before: " + utilizationBasisPointsBefore
                + " BPS after: " + utilizationBasisPointsAfter);
        final var isAcceptable = IntStream.range(0, acceptableMultipliers.length)
                .anyMatch(i -> Math.abs(acceptableMultipliers[i] - observedMultiplier) <= MULTIPLIER_TOLERANCE);
        assertTrue(
                isAcceptable,
                "Given BPS before " + utilizationBasisPointsBefore + " and after " + utilizationBasisPointsAfter
                        + ", observed " + operation + " multiplier " + observedMultiplier
                        + " does not match acceptable multipliers "
                        + Arrays.toString(acceptableMultipliers) + " with " + numTxnsAllowed + " txns allowed");
    }

    private static VisibleItemsValidator feeMultiplierValidator(
            final AtomicReference<List<RecordStreamEntry>> highVolumeTxns) {
        return (spec, records) -> {
            final var items = records.get(ALL_TX_IDS);
            highVolumeTxns.set(items.entries());
        };
    }

    private static ByteString simpleFeesWithoutCryptoCreatePricingCurve() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            final ObjectNode highVolumeRates = findCryptoCreateHighVolumeRates(root);
            highVolumeRates.remove("pricingCurve");
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Unable to build simple fee schedule without CryptoCreate pricing curve", e);
        }
    }

    private static ByteString simpleFeesWithOneXCryptoCreateHighVolumeRates() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            final ObjectNode highVolumeRates = findCryptoCreateHighVolumeRates(root);
            highVolumeRates.put("maxMultiplier", 1000);
            highVolumeRates.remove("pricingCurve");
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build simple fee schedule with 1x CryptoCreate multiplier", e);
        }
    }

    private static ObjectNode findCryptoCreateHighVolumeRates(@NonNull final JsonNode root) {
        for (final var service : root.path("services")) {
            for (final var scheduleEntry : service.path("schedule")) {
                if ("CryptoCreate".equals(scheduleEntry.path("name").asText())) {
                    final var highVolumeRates = scheduleEntry.get("highVolumeRates");
                    if (highVolumeRates instanceof ObjectNode objectNode) {
                        return objectNode;
                    }
                    throw new IllegalStateException("CryptoCreate schedule entry is missing highVolumeRates");
                }
            }
        }
        throw new IllegalStateException("Could not find CryptoCreate entry in simple fee schedule");
    }
}
