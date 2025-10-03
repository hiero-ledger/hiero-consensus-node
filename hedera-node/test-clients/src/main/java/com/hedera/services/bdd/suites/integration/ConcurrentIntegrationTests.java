// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.services.bdd.junit.EmbeddedReason.MANIPULATES_EVENT_VERSION;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateStakingInfos;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateToken;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewMappedValue;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.mutateNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usingEventBirthRound;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(0)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class ConcurrentIntegrationTests {
    private static final Logger log = LogManager.getLogger(ConcurrentIntegrationTests.class);

    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void setupAll() {
        gossipCertificates = generateX509Certificates(1);
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
                blockStreamMustIncludePassFrom(spec -> blockWithResultOf(BUSY)),
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

    @GenesisHapiTest
    @DisplayName("fail invalid during dispatch recharges fees")
    final Stream<DynamicTest> failInvalidDuringDispatchRechargesFees() {
        return hapiTest(
                blockStreamMustIncludePassFrom(spec -> blockWithResultOf(FAIL_INVALID)),
                cryptoCreate("treasury").balance(ONE_HUNDRED_HBARS),
                tokenCreate("token").supplyKey("treasury").treasury("treasury").initialSupply(1L),
                // Corrupt the state by removing the treasury account from the token
                mutateToken("token", token -> token.treasuryAccountId((AccountID) null)),
                burnToken("token", 1L)
                        .payingWith("treasury")
                        .hasKnownStatus(com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID),
                // Confirm the payer was still charged a non-zero fee
                getAccountBalance("treasury")
                        .hasTinyBars(spec -> amount ->
                                Optional.ofNullable(amount == ONE_HUNDRED_HBARS ? "Fee was not recharged" : null)),
                // Make sure genesis block is closed
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @GenesisHapiTest
    @DisplayName("freeze upgrade with sets candidate roster")
    final Stream<DynamicTest> freezeUpgradeWithRosterLifecycleSetsCandidateRoster()
            throws CertificateEncodingException {
        final AtomicReference<ProtoBytes> candidateRosterHash = new AtomicReference<>();
        return hapiTest(
                // Add a node to the candidate roster
                nodeCreate("node4")
                        .adminKey(DEFAULT_PAYER)
                        .accountNum(classicFeeCollectorIdFor(4))
                        .description(CLASSIC_NODE_NAMES[4])
                        .gossipCaCertificate(gossipCertificates.getFirst().getEncoded()),
                mutateNode("4", node -> node.weight(123)),
                // Let few nodes have non-zero stake
                mutateStakingInfos("0", node -> node.stake(ONE_HUNDRED_HBARS)),
                mutateStakingInfos("1", node -> node.stake(ONE_HUNDRED_HBARS)),
                // Submit a valid FREEZE_UPGRADE
                buildUpgradeZipFrom(FAKE_ASSETS_LOC),
                sourcing(() -> updateSpecialFile(
                        GENESIS,
                        DEFAULT_UPGRADE_FILE_ID,
                        FAKE_UPGRADE_ZIP_LOC,
                        TxnUtils.BYTES_4K,
                        upgradeFileAppendsPerBurst())),
                sourcing(() -> prepareUpgrade()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                // Verify the candidate roster is set as part of handling the PREPARE_UPGRADE
                viewSingleton(
                        RosterService.NAME,
                        ROSTER_STATE_STATE_ID,
                        (RosterState rosterState) ->
                                candidateRosterHash.set(new ProtoBytes(rosterState.candidateRosterHash()))),
                sourcing(() -> viewMappedValue(
                        RosterService.NAME, ROSTERS_STATE_ID, candidateRosterHash.get(), (Roster roster) -> {
                            final var entries = roster.rosterEntries();
                            assertEquals(
                                    CLASSIC_HAPI_TEST_NETWORK_SIZE + 1,
                                    entries.size(),
                                    "Wrong number of entries in candidate roster");
                        })));
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
