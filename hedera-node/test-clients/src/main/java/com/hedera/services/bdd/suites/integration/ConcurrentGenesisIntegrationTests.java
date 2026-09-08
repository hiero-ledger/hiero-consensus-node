// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateStakingInfos;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateToken;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewMappedValue;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildUpgradeZipFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.mutateNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.prepareUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateSpecialFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.FAKE_ASSETS_LOC;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileAppendsPerBurst;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Genesis tests build their own network, so they must run before any shared network exists
@Order(Integer.MIN_VALUE)
@Tag(INTEGRATION)
@TargetEmbeddedMode(CONCURRENT)
public class ConcurrentGenesisIntegrationTests {
    private static List<X509Certificate> gossipCertificates;

    @BeforeAll
    static void setupAll() {
        gossipCertificates = generateX509Certificates(2);
    }

    @GenesisHapiTest
    @DisplayName("fail invalid during dispatch recharges fees")
    final Stream<DynamicTest> failInvalidDuringDispatchRechargesFees() {
        return hapiTest(
                streamMustIncludePassFrom(spec -> blockWithResultOf(FAIL_INVALID)),
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
                nodeCreate("node4", classicFeeCollectorIdFor(4))
                        .adminKey(DEFAULT_PAYER)
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

    @GenesisHapiTest
    @DisplayName("candidate roster retains metadata when reweighted at stake boundary")
    final Stream<DynamicTest> candidateRosterRetainsMetadataWhenReweightedAtStakeBoundary()
            throws CertificateEncodingException {
        final AtomicReference<ProtoBytes> activeRosterHash = new AtomicReference<>();
        final AtomicReference<Bytes> oldRosterCert = new AtomicReference<>();
        final AtomicReference<ProtoBytes> candidateRosterHash = new AtomicReference<>();
        final AtomicLong candidateWeightBeforeBoundary = new AtomicLong();
        final byte[] updatedCert = gossipCertificates.get(1).getEncoded();

        return hapiTest(
                overriding("staking.periodMins", "1"),
                // Capture the current active roster cert for node0
                viewSingleton(
                        RosterService.NAME,
                        ROSTER_STATE_STATE_ID,
                        (RosterState rosterState) -> activeRosterHash.set(new ProtoBytes(
                                rosterState.roundRosterPairs().getFirst().activeRosterHash()))),
                sourcing(() -> viewMappedValue(
                        RosterService.NAME, ROSTERS_STATE_ID, activeRosterHash.get(), (Roster roster) -> {
                            final var node0Entry = roster.rosterEntries().stream()
                                    .filter(e -> e.nodeId() == 0L)
                                    .findFirst()
                                    .orElseThrow();
                            oldRosterCert.set(node0Entry.gossipCaCertificate());
                        })),
                // Update node0's gossip CA certificate via DAB NodeUpdate
                nodeUpdate("0").gossipCaCertificate(updatedCert),
                // Run PREPARE_UPGRADE (will snapshot candidate roster from node state)
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
                // Read candidate roster from state and ensure it includes the updated cert
                viewSingleton(
                        RosterService.NAME,
                        ROSTER_STATE_STATE_ID,
                        (RosterState rosterState) ->
                                candidateRosterHash.set(new ProtoBytes(rosterState.candidateRosterHash()))),
                sourcing(() -> viewMappedValue(
                        RosterService.NAME, ROSTERS_STATE_ID, candidateRosterHash.get(), (Roster roster) -> {
                            final var node0Entry = roster.rosterEntries().stream()
                                    .filter(e -> e.nodeId() == 0L)
                                    .findFirst()
                                    .orElseThrow();
                            candidateWeightBeforeBoundary.set(node0Entry.weight());
                            assertEquals(
                                    Bytes.wrap(updatedCert),
                                    node0Entry.gossipCaCertificate(),
                                    "Candidate roster did not reflect updated cert");
                        })),
                // Seed stakeToReward so EndOfStakingPeriodUpdater computes non-zero stakes/weights at the boundary
                // Note: ReadableStakingInfoStore.weightFunction() uses nodeInfo.stake(), but EndOfStakingPeriodUpdater
                // recomputes stake from stakeToReward + stakeToNotReward at the boundary (so we set stakeToReward
                // here).
                mutateStakingInfos("0", node -> node.stakeToReward(ONE_HUNDRED_HBARS)),
                mutateStakingInfos("1", node -> node.stakeToReward(ONE_HUNDRED_HBARS)),
                mutateStakingInfos("2", node -> node.stakeToReward(ONE_HUNDRED_HBARS)),
                mutateStakingInfos("3", node -> node.stakeToReward(ONE_HUNDRED_HBARS)),
                // Cross a staking period boundary and submit a txn to trigger stake period side effects
                waitUntilStartOfNextStakingPeriod(1),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                // Re-read candidate roster and ensure the updated cert was not lost during reweighting
                viewSingleton(
                        RosterService.NAME,
                        ROSTER_STATE_STATE_ID,
                        (RosterState rosterState) ->
                                candidateRosterHash.set(new ProtoBytes(rosterState.candidateRosterHash()))),
                sourcing(() -> viewMappedValue(
                        RosterService.NAME, ROSTERS_STATE_ID, candidateRosterHash.get(), (Roster roster) -> {
                            final var node0Entry = roster.rosterEntries().stream()
                                    .filter(e -> e.nodeId() == 0L)
                                    .findFirst()
                                    .orElseThrow();
                            assertEquals(
                                    Bytes.wrap(updatedCert),
                                    node0Entry.gossipCaCertificate(),
                                    "Stake-boundary reweighting overwrote candidate roster metadata");
                            assertNotEquals(
                                    oldRosterCert.get(),
                                    node0Entry.gossipCaCertificate(),
                                    "Reweighted candidate roster unexpectedly matches old active roster cert");
                            assertEquals(
                                    ONE_HUNDRED_HBARS,
                                    node0Entry.weight(),
                                    "Candidate roster did not reflect recalculated (non-zero) staking weight");
                            assertNotEquals(
                                    candidateWeightBeforeBoundary.get(),
                                    node0Entry.weight(),
                                    "Candidate roster weight did not change at stake boundary");
                        })));
    }

    private static BlockStreamAssertion blockWithResultOf(@NonNull final ResponseCodeEnum status) {
        return block -> block.items().stream()
                .filter(BlockItem::hasTransactionResult)
                .map(BlockItem::transactionResultOrThrow)
                .map(TransactionResult::status)
                .anyMatch(status::equals);
    }
}
