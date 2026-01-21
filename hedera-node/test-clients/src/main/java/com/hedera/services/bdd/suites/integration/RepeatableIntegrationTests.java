// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.NonFungibleTransfers.changingNFTBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.handleAnyRepeatableQueryPayment;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.throttle.CongestionThrottleService;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(1)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableIntegrationTests {
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    Stream<DynamicTest> burnAtStakePeriodBoundaryHasExpectedRecord(
            @NonFungibleToken(numPreMints = 2) SpecNonFungibleToken nft) {
        return hapiTest(
                nft.getInfo(),
                doWithStartupConfig(
                        "staking.periodMins", value -> waitUntilStartOfNextStakingPeriod(Long.parseLong(value))),
                burnToken(nft.name(), List.of(1L)).via("burn"),
                getTxnRecord("burn")
                        .hasPriority(recordWith()
                                .tokenTransfers(changingNFTBalances()
                                        .including(nft.name(), nft.treasury().name(), "0.0.0", 1L))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> senderSignatureValidatedInQueries() {
        final var PAYER = "payer";
        final var SENDER = "sender";
        final var SENDER_BALANCE = ONE_MILLION_HBARS;
        final AtomicLong initialNodeBalance = new AtomicLong();
        final var badPayment = cryptoTransfer(tinyBarsFromTo(SENDER, "3", ONE_HUNDRED_HBARS))
                .payingWith(PAYER)
                .signedBy(PAYER);
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SENDER).balance(SENDER_BALANCE),
                getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                getAccountInfo(SENDER)
                        .withPayment(badPayment)
                        .hasAnswerOnlyPrecheck(INVALID_SIGNATURE)
                        .logged(),
                handleAnyRepeatableQueryPayment(),
                getAccountBalance(SENDER).hasTinyBars(SENDER_BALANCE),
                sourcing(() ->getAccountBalance("3").hasTinyBars(initialNodeBalance.get()))
        );
    }

    @RepeatableHapiTest({NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION, NEEDS_STATE_ACCESS})
    Stream<DynamicTest> gasThrottleMimicsThroughputThrottleCongestionStatus() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER),
                sourcingContextual(spec -> mutateSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots usageSnapshots) -> usageSnapshots
                                .copyBuilder()
                                .gasThrottle(ThrottleUsageSnapshot.newBuilder()
                                        .lastDecisionTime(asTimestamp(spec.consensusTime()))
                                        .used(1L)
                                        .build())
                                .build())),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_PAYER, FUNDING, 1)).payingWith(CIVILIAN_PAYER),
                viewSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots usageSnapshots) -> assertEquals(
                                0, usageSnapshots.gasThrottleOrThrow().used())));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> classifiableTakesPriorityOverUnclassifiable() {
        return hapiTest(
                cryptoCreate("civilian").balance(100 * 100_000_000L),
                usableTxnIdNamed("txnId").payerId("civilian"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000L)),
                uncheckedSubmit(cryptoCreate("nope")
                        .txnId("txnId")
                        .payingWith("civilian")
                        .setNode("4")),
                uncheckedSubmit(cryptoCreate("sure")
                        .txnId("txnId")
                        .payingWith("civilian")
                        .setNode("3")),
                getReceipt("txnId")
                        .andAnyDuplicates()
                        .hasPriorityStatus(SUCCESS)
                        .hasDuplicateStatuses(INVALID_NODE_ACCOUNT),
                getTxnRecord("txnId")
                        .assertingNothingAboutHashes()
                        .andAnyDuplicates()
                        .hasPriority(recordWith().status(SUCCESS))
                        .hasDuplicates(inOrder(recordWith().status(INVALID_NODE_ACCOUNT))));
    }
}
