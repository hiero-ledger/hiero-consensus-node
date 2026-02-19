// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
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
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.PiecewiseLinearCurve;
import org.hiero.hapi.support.fees.PiecewiseLinearPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
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
                        .exposingAllTo(
                                records -> assertOnlyChildRecordsHaveHighVolumeMultiplier(records, "autoCreation"))
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
                                assertNoRecordHasHighVolumeMultiplier(records, "autoCreationWithoutHighVolume"))
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
                        .exposingAllTo(records -> assertRecordHasHighVolumeMultiplier(records, "autoCreation", 4000L))
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
                        .exposingAllTo(
                                records -> assertNoRecordHasHighVolumeMultiplier(records, "airdropWithoutHighVolume"))
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
                        .exposingAllTo(records ->
                                assertRecordHasHighVolumeMultiplierGreaterThan(records, "claimAirdrop", 1000L))
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
                                assertNoRecordHasHighVolumeMultiplier(records, "claimAirdropWithoutHighVolume"))
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
                        .exposingAllTo(records -> assertNoRecordHasHighVolumeMultiplier(records, "highVolumeUpdate"))
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
                        .exposingAllTo(records -> assertRecordHasHighVolumeMultiplier(records, "plainTransfer", 1000L))
                        .logged(),
                validateChargedUsdWithChild("plainTransfer", 0.0001, 0.01));
    }

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/hip1313-disabled-one-tps-create.json")
    final Stream<DynamicTest> highVolumeTxnFallsBackToNormalThrottleWhenNoHighVolumeBucketExists() {
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-disabled-one-tps-create.json"),
                cryptoCreate("fallbackThrottleA")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
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
                    for (final var entry : entries) {
                        final var utilizationBasisPointsBefore = utilizationBasisPointsBefore(throttle);
                        throttle.allow(1, entry.consensusTime());
                        assertHighVolumeMultiplierSet(entry, "crypto create");
                        final var fee = entry.txnRecord().getTransactionFee();
                        final var observedMultiplier = observedMultiplier(spec, fee, CRYPTO_CREATE_BASE_FEE);
                        final var expectedMultiplier = getInterpolatedMultiplier(
                                        CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP, utilizationBasisPointsBefore)
                                / 1000.0;
                        assertMultiplierAtLeast(observedMultiplier, "crypto create");
                        assertMultiplierMatchesExpectation(
                                expectedMultiplier, observedMultiplier, utilizationBasisPointsBefore, "crypto create");
                    }
                    assertEquals(200, entries.size());
                }));
    }

    @GenesisHapiTest
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
                            final var utilizationBasisPointsBefore = utilizationBasisPointsBefore(topicThrottle);
                            topicThrottle.allow(1, entry.consensusTime());
                            assertHighVolumeMultiplierSet(entry, "topic create");
                            final var observedMultiplier = observedMultiplier(spec, fee, TOPIC_CREATE_BASE_FEE);
                            final var expectedMultiplier = getInterpolatedMultiplier(
                                            CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP, utilizationBasisPointsBefore)
                                    / 1000.0;
                            assertMultiplierAtLeast(observedMultiplier, "topic create");
                            assertMultiplierMatchesExpectation(
                                    expectedMultiplier,
                                    observedMultiplier,
                                    utilizationBasisPointsBefore,
                                    "topic create");
                            topicCreates++;
                        } else if (entry.body().hasScheduleCreate()) {
                            final var utilizationBasisPointsBefore = utilizationBasisPointsBefore(scheduleThrottle);
                            scheduleThrottle.allow(1, entry.consensusTime());
                            assertHighVolumeMultiplierSet(entry, "schedule create");
                            final var observedMultiplier = observedMultiplier(spec, fee, SCHEDULE_CREATE_BASE_FEE);
                            final var expectedMultiplier = getInterpolatedMultiplier(
                                            SCHEDULE_CREATE_MULTIPLIER_MAP, utilizationBasisPointsBefore)
                                    / 1000.0;
                            assertMultiplierMatchesExpectation(
                                    expectedMultiplier,
                                    observedMultiplier,
                                    utilizationBasisPointsBefore,
                                    "schedule create");
                            scheduleCreates++;
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
                            final var utilizationBasisPointsBefore = utilizationBasisPointsBefore(throttle);
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
                        .exposingAllTo(records ->
                                assertRecordHasHighVolumeMultiplier(records, "defaultMultiplierCreateTxn", 1000L))
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
                // Expected multipliers are derived from utilization progression; process
                // records in consensus order to avoid nondeterministic flakiness.
                .sorted()
                .toList();
    }

    private static int utilizationBasisPointsBefore(@NonNull final DeterministicThrottle throttle) {
        return (int) Math.round(throttle.instantaneousPercentUsed() * 100);
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
                multiplier >= 4000L,
                "Expected " + operation + " high-volume multiplier to be set (>4), but was " + multiplier);
    }

    private static void assertRecordHasHighVolumeMultiplierGreaterThan(
            @NonNull final List<TransactionRecord> records, @NonNull final String operation, final long multiplier) {
        final var hasHighVolumeMultiplier =
                records.stream().anyMatch(record -> record.getHighVolumePricingMultiplier() > multiplier);
        assertTrue(
                hasHighVolumeMultiplier,
                "Expected " + operation + " to include a record with high-volume multiplier set (>1)");
    }

    private static void assertRecordHasHighVolumeMultiplier(
            @NonNull final List<TransactionRecord> records, @NonNull final String operation, final long multiplier) {
        final var hasDefaultMultiplier =
                records.stream().anyMatch(record -> record.getHighVolumePricingMultiplier() == multiplier);
        final var hasBoostedMultiplier =
                records.stream().anyMatch(record -> record.getHighVolumePricingMultiplier() > multiplier);
        assertTrue(
                hasDefaultMultiplier,
                "Expected " + operation + " to include a record with default high-volume multiplier (1x)");
        assertFalse(
                hasBoostedMultiplier,
                "Expected " + operation + " not to include a record with boosted high-volume multiplier (>1x)");
    }

    private static void assertNoRecordHasHighVolumeMultiplier(
            @NonNull final List<TransactionRecord> records, @NonNull final String operation) {
        final var hasHighVolumeMultiplier =
                records.stream().anyMatch(record -> record.getHighVolumePricingMultiplier() > 0L);
        assertFalse(
                hasHighVolumeMultiplier,
                "Expected " + operation + " to have no record with high-volume multiplier set (>0)");
    }

    private static void assertOnlyChildRecordsHaveHighVolumeMultiplier(
            @NonNull final List<TransactionRecord> records, @NonNull final String operation) {
        final var hasChildMultiplier = records.stream()
                .anyMatch(record ->
                        record.getTransactionID().getNonce() > 0 && record.getHighVolumePricingMultiplier() > 1000L);
        final var parentHasMultiplier = records.stream()
                .anyMatch(record ->
                        record.getTransactionID().getNonce() == 0 && record.getHighVolumePricingMultiplier() > 1000L);
        assertTrue(
                hasChildMultiplier,
                "Expected " + operation + " to include a child record with high-volume multiplier set (>1)");
        assertFalse(
                parentHasMultiplier,
                "Expected " + operation + " parent record to use default (non-high-volume) multiplier");
    }

    private static void assertMultiplierMatchesExpectation(
            final double expectedMultiplier,
            final double observedMultiplier,
            final int utilizationBasisPointsBefore,
            @NonNull final String operation) {
        assertEquals(
                expectedMultiplier,
                observedMultiplier,
                MULTIPLIER_TOLERANCE,
                "Given BPS of " + utilizationBasisPointsBefore + " observed " + operation + " multiplier "
                        + observedMultiplier + " does not match expected multiplier " + expectedMultiplier);
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
            final JsonNode root = MAPPER.readTree(TxnUtils.resourceAsString("genesis/simpleFeesSchedules.json"));
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
            final JsonNode root = MAPPER.readTree(TxnUtils.resourceAsString("genesis/simpleFeesSchedules.json"));
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
