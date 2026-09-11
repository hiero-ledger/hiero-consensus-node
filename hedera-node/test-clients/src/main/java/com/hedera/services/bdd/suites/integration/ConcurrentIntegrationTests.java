// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.services.bdd.junit.EmbeddedReason.MANIPULATES_EVENT_VERSION;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.WIPE_KEY;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usingEventBirthRound;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.aaWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(0)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class ConcurrentIntegrationTests {
    @EmbeddedHapiTest(MUST_SKIP_INGEST)
    final Stream<DynamicTest> pureCheckFailureIsChargedToSubmittingNode() {
        final var dueDiligenceTxn = "pureCheckDueDiligenceTxn";
        final var party = "party";
        final var counterparty = "counterparty";
        final var otherAccount = "otherAccount";

        return hapiTest(
                cryptoCreate(party).balance(0L),
                cryptoCreate(counterparty).balance(0L),
                cryptoCreate(otherAccount).balance(0L),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "4", ONE_HBAR)),
                cryptoTransfer((spec, b) -> b.setTransfers(TransferList.newBuilder()
                                .addAccountAmounts(aaWith(spec.registry().getAccountID(party), +Long.MAX_VALUE))
                                .addAccountAmounts(aaWith(spec.registry().getAccountID(otherAccount), +Long.MAX_VALUE))
                                .addAccountAmounts(aaWith(spec.registry().getAccountID(counterparty), +2))))
                        .signedBy(DEFAULT_PAYER)
                        .via(dueDiligenceTxn)
                        .setNode("4")
                        .hasKnownStatus(INVALID_ACCOUNT_AMOUNTS),
                validateChargedAccount(dueDiligenceTxn, "4"));
    }

    @HapiTest
    @DisplayName("hollow account completion happens even with unsuccessful txn")
    final Stream<DynamicTest> hollowAccountCompletionHappensEvenWithUnsuccessfulTxn() {
        return hapiTest(
                tokenCreate("token").treasury(DEFAULT_PAYER).initialSupply(123L),
                cryptoCreate("unassociated"),
                createHollow(
                        1,
                        i -> "hollowAccount",
                        evmAddress -> cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))),
                cryptoTransfer(TokenMovement.moving(1, "token").between(DEFAULT_PAYER, "unassociated"))
                        .payingWith("hollowAccount")
                        .sigMapPrefixes(uniqueWithFullPrefixesFor("hollowAccount"))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getAccountInfo("hollowAccount").isNotHollow());
    }

    @EmbeddedHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> wipingZeroFromZeroBalanceIsNoopForPositiveCounts(
            @Account SpecAccount wipeTarget,
            @FungibleToken SpecFungibleToken miscToken,
            @FungibleToken(keys = {WIPE_KEY}) SpecFungibleToken wipeableFungibleToken) {
        return hapiTest(
                wipeTarget.associateTokens(miscToken, wipeableFungibleToken),
                cryptoTransfer(TokenMovement.moving(1, miscToken.name())
                        .between(miscToken.treasury().name(), wipeTarget.name())),
                viewAccount(wipeTarget.name(), (a) -> assertEquals(1, a.numberPositiveBalances())),
                wipeTokenAccount(wipeableFungibleToken.name(), wipeTarget.name(), 0),
                viewAccount(wipeTarget.name(), (a) -> assertEquals(1, a.numberPositiveBalances())));
    }

    @HapiTest
    final Stream<DynamicTest> chargedFeesReplayedAfterBatchFailure(
            @NonFungibleToken(numPreMints = 10) SpecNonFungibleToken nftOne,
            @NonFungibleToken(numPreMints = 10) SpecNonFungibleToken nftTwo) {
        final List<SortedMap<AccountID, Long>> successfulRecordFees = new ArrayList<>();
        return hapiTest(
                cryptoCreate("operator").maxAutomaticTokenAssociations(2),
                nftOne.doWith(
                        token -> cryptoTransfer(movingUnique(nftOne.name(), 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                                .between(nftOne.treasury().name(), "operator"))),
                nftTwo.doWith(
                        token -> cryptoTransfer(movingUnique(nftTwo.name(), 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                                .between(nftTwo.treasury().name(), "operator"))),
                // First do a batch where everything succeeds
                atomicBatch(
                                cryptoTransfer(movingUnique(nftOne.name(), 1L)
                                                .between(
                                                        "operator",
                                                        nftOne.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator"),
                                cryptoTransfer(movingUnique(nftOne.name(), 2L, 3L)
                                                .between(
                                                        "operator",
                                                        nftOne.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator"),
                                cryptoTransfer(movingUnique(nftOne.name(), 4L, 5L, 6L)
                                                .between(
                                                        "operator",
                                                        nftOne.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator"))
                        .signedByPayerAnd("operator"),
                getAccountRecords("operator").exposingTo(records -> {
                    assertEquals(3, records.size());
                    records.forEach(r -> successfulRecordFees.add(asMap(r.getTransferList())));
                }),
                // Now change max transfer len and do a batch where the last fails
                overriding("ledger.nftTransfers.maxLen", "2"),
                atomicBatch(
                                cryptoTransfer(movingUnique(nftTwo.name(), 1L)
                                                .between(
                                                        "operator",
                                                        nftTwo.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator"),
                                cryptoTransfer(movingUnique(nftTwo.name(), 2L, 3L)
                                                .between(
                                                        "operator",
                                                        nftTwo.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator"),
                                cryptoTransfer(movingUnique(nftTwo.name(), 4L, 5L, 6L)
                                                .between(
                                                        "operator",
                                                        nftTwo.treasury().name()))
                                        .batchKey("operator")
                                        .payingWith("operator")
                                        .hasKnownStatus(BATCH_SIZE_LIMIT_EXCEEDED))
                        .signedByPayerAnd("operator")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getAccountRecords("operator").exposingTo(records -> {
                    assertEquals(6, records.size());
                    final var nextRecords = records.subList(3, 6);
                    final List<SortedMap<AccountID, Long>> unsuccessfulRecordFees = new ArrayList<>();
                    nextRecords.forEach(r -> unsuccessfulRecordFees.add(asMap(r.getTransferList())));
                    for (int i = 0; i < 3; i++) {
                        assertEquals(
                                successfulRecordFees.get(i),
                                unsuccessfulRecordFees.get(i),
                                "Wrong fees at inner txn index=" + i);
                    }
                }));
    }

    @EmbeddedHapiTest(MANIPULATES_EVENT_VERSION)
    @DisplayName("skips pre-upgrade event and streams result with BUSY status")
    final Stream<DynamicTest> skipsStaleEventWithBusyStatus() {
        return hapiTest(
                streamMustIncludePassFrom(spec -> blockWithResultOf(BUSY)),
                cryptoCreate("somebody").balance(0L),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "somebody", ONE_HBAR))
                        .setNode(4)
                        .withSubmissionStrategy(usingEventBirthRound(-1))
                        .hasKnownStatus(com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY),
                getAccountBalance("somebody").hasTinyBars(0L),
                // Trigger block closure to ensure block is closed
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @EmbeddedHapiTest(MANIPULATES_EVENT_VERSION)
    @DisplayName("completely skips transaction from unknown node")
    final Stream<DynamicTest> completelySkipsTransactionFromUnknownNode() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, ONE_HBAR))
                        .setNode(666)
                        .via("toBeSkipped")
                        .withSubmissionStrategy(usingEventBirthRound(55L))
                        .hasAnyStatusAtAll(),
                getTxnRecord("toBeSkipped").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    private static BlockStreamAssertion blockWithResultOf(@NonNull final ResponseCodeEnum status) {
        return block -> block.items().stream()
                .filter(BlockItem::hasTransactionResult)
                .map(BlockItem::transactionResultOrThrow)
                .map(TransactionResult::status)
                .anyMatch(status::equals);
    }

    private static SortedMap<AccountID, Long> asMap(@NonNull final TransferList list) {
        return list.getAccountAmountsList().stream()
                .map(aa -> AccountAmount.newBuilder()
                        .accountID(CommonPbjConverters.toPbj(aa.getAccountID()))
                        .amount(aa.getAmount())
                        .build())
                .collect(Collectors.toMap(
                        AccountAmount::accountID,
                        AccountAmount::amount,
                        Long::sum,
                        () -> new TreeMap<>(ACCOUNT_ID_COMPARATOR)));
    }
}
