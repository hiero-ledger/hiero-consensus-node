// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.SharedNetworkLauncherSessionListener.CLASSIC_HAPI_TEST_NETWORK_SIZE;
import static com.hedera.services.bdd.junit.TestTags.UPGRADE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.CLASSIC_NODE_NAMES;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.entryById;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.nodeIdsFrom;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.VALID_CERT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asReadableIp;
import static com.hedera.services.bdd.spec.HapiPropertySource.asServiceEndpoint;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.dsl.operations.transactions.TouchBalancesOperation.touchBalanceOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.sysFileUpdateTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ensureStakingActivated;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateCandidateRoster;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator.EXISTENCE_ONLY_VALIDATOR;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.configVersionOf;
import static com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo.nodeDetailsFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_NODES_CREATED;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.props.JutilPropertySource;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.suites.utils.sysfiles.AddressBookPojo;
import com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Asserts expected behavior of the network when upgrading with DAB enabled.
 * <p>
 * The test framework simulates DAB by copying the <i>config.txt</i> from the node's upgrade artifacts into their
 * working directories, instead of regenerating a <i>config.txt</i> to match its {@link HederaNode} instances. It
 * <p>
 * There are three upgrades in this test. The first leaves the address book unchanged, the second removes `node1`,
 * and the last one adds a new `node5`.
 * <p>
 * Halfway through the sequence, we also verify that reconnect is still possible  with only `node0` and `node2`
 * left online while `node3` reconnects; which we accomplish by giving most of the stake to those nodes.
 * <p>
 * We also verify that an account staking to a deleted node cannot earn rewards.
 * <p>
 * See <a href="https://github.com/hashgraph/hedera-improvement-proposal/blob/main/HIP/hip-869.md#user-stories">here</a>
 * for the associated HIP-869 user stories.
 * <p>
 * Since this test upgrades the software version, it must run after any other test that does a restart assuming
 * the config version is still zero.
 */
@Tag(UPGRADE)
@Order(Integer.MAX_VALUE - 2)
@HapiTestLifecycle
@OrderedInIsolation
public class DabEnabledUpgradeTest implements LifecycleTest {
    private static final String SHARD = JutilPropertySource.getDefaultInstance().get("default.shard");
    private static final String REALM = JutilPropertySource.getDefaultInstance().get("default.realm");
    private static final List<String> NODE_ACCOUNT_IDS = List.of("0.0.3", "0.0.4", "0.0.5", "0.0.6");

    // To test BirthRoundStateMigration, use,
    //    Map.of("event.useBirthRoundAncientThreshold", "true")
    private static final Map<String, String> ENV_OVERRIDES = Map.of();

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @Account(tinybarBalance = ONE_MILLION_HBARS, stakedNodeId = 3)
    static SpecAccount NODE3_STAKER;

    @HapiTest
    @Order(0)
    final Stream<DynamicTest> addressBookAndNodeDetailsPopulated() {
        final var file101 = String.format("%s.%s.%d", SHARD, REALM, 101);
        final var file102 = String.format("%s.%s.%d", SHARD, REALM, 102);

        return hapiTest(
                QueryVerbs.getFileContents(file101).consumedBy(bytes -> {
                    AddressBookPojo addressBook;
                    try {
                        addressBook = AddressBookPojo.addressBookFrom(NodeAddressBook.parseFrom(bytes));
                    } catch (InvalidProtocolBufferException e) {
                        fail("Failed to parse address book", e);
                        throw new IllegalStateException("Needed for compilation; should never happen");
                    }
                    verifyAddressInfo(addressBook);
                }),
                QueryVerbs.getFileContents(file102).consumedBy(bytes -> {
                    final AddressBookPojo pojoBook;
                    try {
                        pojoBook = nodeDetailsFrom(NodeAddressBook.parseFrom(bytes));
                    } catch (InvalidProtocolBufferException e) {
                        fail("Failed to parse node details", e);
                        throw new IllegalStateException("Needed for compilation; should never happen");
                    }

                    verifyAddressInfo(pojoBook);
                }));
    }

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> upgradeWithSameNodesExportsTheOriginalAddressBook() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        EXISTENCE_ONLY_VALIDATOR, 2, sysFileUpdateTo("files.nodeDetails", "files.addressBook"))),
                // This test verifies staking rewards aren't paid for deleted nodes; so ensure staking is active
                ensureStakingActivated(),
                touchBalanceOf(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER, NODE3_STAKER),
                waitUntilStartOfNextStakingPeriod(1).withBackgroundTraffic(),
                // Now do the first upgrade
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                prepareFakeUpgrade(),
                validateCandidateRoster(DabEnabledUpgradeTest::hasClassicRosterMetadata),
                upgradeToNextConfigVersion(),
                assertGetVersionInfoMatches(startVersion::get));
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> nodeId1NotInCandidateRosterAfterRemovalAndStakerNotRewardedAfterUpgrade() {
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        EXISTENCE_ONLY_VALIDATOR, 2, sysFileUpdateTo("files.nodeDetails", "files.addressBook"))),
                nodeDelete("1"),
                prepareFakeUpgrade(),
                validateCandidateRoster(
                        addressBook -> assertThat(nodeIdsFrom(addressBook)).containsExactlyInAnyOrder(0L, 2L, 3L)),
                upgradeToNextConfigVersion(ENV_OVERRIDES, FakeNmt.removeNode(byNodeId(1))),
                waitUntilStartOfNextStakingPeriod(1).withBackgroundTraffic(),
                touchBalanceOf(NODE0_STAKER, NODE2_STAKER, NODE3_STAKER).andAssertStakingRewardCount(3),
                touchBalanceOf(NODE1_STAKER).andAssertStakingRewardCount(0));
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> nodeId3CanStillReconnectAfterRemovingNodeId1() {
        final AtomicReference<SemanticVersion> startVersion = new AtomicReference<>();
        return hapiTest(
                getVersionInfo().exposingServicesVersionTo(startVersion::set),
                sourcing(() -> reconnectNode(byNodeId(3), configVersionOf(startVersion.get()))));
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> nodeId3NotInCandidateRosterAfterRemovalAndStakerNotRewardedAfterUpgrade() {
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        EXISTENCE_ONLY_VALIDATOR, 2, sysFileUpdateTo("files.nodeDetails", "files.addressBook"))),
                nodeDelete("3"),
                prepareFakeUpgrade(),
                validateCandidateRoster(
                        addressBook -> assertThat(nodeIdsFrom(addressBook)).containsExactlyInAnyOrder(0L, 2L)),
                upgradeToNextConfigVersion(ENV_OVERRIDES, FakeNmt.removeNode(byNodeId(3))),
                waitUntilStartOfNextStakingPeriod(1).withBackgroundTraffic(),
                touchBalanceOf(NODE0_STAKER, NODE2_STAKER).andAssertStakingRewardCount(2),
                touchBalanceOf(NODE3_STAKER).andAssertStakingRewardCount(0));
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> newNodeId4InCandidateRosterAfterAddition() {
        return hapiTest(
                recordStreamMustIncludePassFrom(selectedItems(
                        EXISTENCE_ONLY_VALIDATOR, 2, sysFileUpdateTo("files.nodeDetails", "files.addressBook"))),
                nodeCreate("node4")
                        .adminKey(DEFAULT_PAYER)
                        .accountId(classicFeeCollectorIdFor(4))
                        .description(CLASSIC_NODE_NAMES[4])
                        .withAvailableSubProcessPorts()
                        .gossipCaCertificate(VALID_CERT),
                prepareFakeUpgrade(),
                // node4 was not active before this the upgrade, so it could not have written a config.txt
                validateCandidateRoster(exceptNodeIds(4L), addressBook -> assertThat(nodeIdsFrom(addressBook))
                        .contains(4L)),
                upgradeToNextConfigVersion(ENV_OVERRIDES, FakeNmt.addNode(4L)));
    }

    @Nested
    @Order(6)
    @DisplayName("with multipart DAB edits before and after prepare upgrade")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class WithMultipartDabEditsBeforeAndAfterPrepareUpgrade {
        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.overrideInClass(Map.of(
                    // Note that deleted nodes still count against the max number of nodes
                    "nodes.maxNumber", "7",
                    "nodes.updateAccountIdAllowed", "true"));
            // Do a combination of node creates, deletes, and updates with a disallowed create in the middle;
            // all the successful edits here are before issuing PREPARE_UPGRADE and should be reflected in the
            // address book after the upgrade
            testLifecycle.doAdhoc(
                    nodeCreate("node5")
                            .adminKey(DEFAULT_PAYER)
                            .accountId(classicFeeCollectorIdFor(5))
                            .description(CLASSIC_NODE_NAMES[5])
                            .withAvailableSubProcessPorts()
                            .gossipCaCertificate(VALID_CERT),
                    nodeCreate("toBeDeletedNode6")
                            .adminKey(DEFAULT_PAYER)
                            .accountId(classicFeeCollectorIdFor(6))
                            .description(CLASSIC_NODE_NAMES[6])
                            .withAvailableSubProcessPorts()
                            .gossipCaCertificate(VALID_CERT),
                    nodeCreate("disallowedNode7")
                            .adminKey(DEFAULT_PAYER)
                            .accountId(classicFeeCollectorIdFor(7))
                            .description(CLASSIC_NODE_NAMES[7])
                            .withAvailableSubProcessPorts()
                            .gossipCaCertificate(VALID_CERT)
                            .hasKnownStatus(MAX_NODES_CREATED),
                    // Delete a pending node
                    nodeDelete("6"),
                    // Delete an already active node
                    nodeDelete("4"),
                    // Update a pending node
                    nodeUpdate("node5")
                            // These endpoints will be replaced by the FakeNmt process just before
                            // restart but can still be validated in the DAB-generated config.txt
                            .gossipEndpoint(
                                    List.of(asServiceEndpoint("127.0.0.1:33000"), asServiceEndpoint("127.0.0.1:33001")))
                            .accountId(classicFeeCollectorIdLiteralFor(905)),
                    // Update an existing node
                    nodeUpdate("2").accountId(classicFeeCollectorIdLiteralFor(902)));
        }

        @HapiTest
        @Order(0)
        @DisplayName("exported address book reflects only edits before prepare upgrade")
        final Stream<DynamicTest> exportedAddressBookReflectsOnlyEditsBeforePrepareUpgrade() {
            return hapiTest(
                    recordStreamMustIncludePassFrom(selectedItems(
                            EXISTENCE_ONLY_VALIDATOR, 2, sysFileUpdateTo("files.nodeDetails", "files.addressBook"))),
                    prepareFakeUpgrade(),
                    // Now make some changes that should not be incorporated in this upgrade
                    nodeDelete("5"),
                    nodeDelete("2"),
                    validateCandidateRoster(
                            NodeSelector.allNodes(), DabEnabledUpgradeTest::validateNodeId5MultipartEdits),
                    upgradeToNextConfigVersion(
                            ENV_OVERRIDES, FakeNmt.removeNode(NodeSelector.byNodeId(4L)), FakeNmt.addNode(5L)),
                    // Validate that nodeId2 and nodeId5 have their new fee collector account IDs,
                    // since those were updated before the prepare upgrade
                    cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                            .setNode(classicFeeCollectorIdLiteralFor(902)),
                    cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                            .setNode(classicFeeCollectorIdLiteralFor(905)),
                    // Validate that nodeId0 still has the classic fee collector account ID, since
                    // it was updated after the prepare upgrade
                    cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                            .setNode(classicFeeCollectorIdLiteralFor(0)));
        }
    }

    private static void verifyAddressInfo(final AddressBookPojo addressBook) {
        final var entries = addressBook.getEntries().stream()
                .map(BookEntryPojo::getNodeAccount)
                .toList();
        assertThat(entries).hasSizeGreaterThanOrEqualTo(NODE_ACCOUNT_IDS.size());
        entries.forEach(nodeId -> assertThat(NODE_ACCOUNT_IDS).contains(nodeId));
    }

    /**
     * Validates that {@code node5} in the given roster is as expected after the multipart edits.
     * @param roster the roster to validate
     */
    private static void validateNodeId5MultipartEdits(@NonNull final Roster roster) {
        final var node5 = entryById(roster, 5L);
        final var externalEndpoint = node5.gossipEndpoint().getFirst();
        final var internalEndpoint = node5.gossipEndpoint().getLast();
        assertEquals("127.0.0.1", asReadableIp(internalEndpoint.ipAddressV4()));
        assertEquals(33000, internalEndpoint.port());
        assertEquals("127.0.0.1", asReadableIp(externalEndpoint.ipAddressV4()));
        assertEquals(33001, externalEndpoint.port());
    }

    private static void hasClassicRosterMetadata(@NonNull final Roster roster) {
        final var entries = roster.rosterEntries();
        assertEquals(CLASSIC_HAPI_TEST_NETWORK_SIZE, entries.size(), "Wrong size");
        final var classicIds =
                LongStream.range(0, CLASSIC_HAPI_TEST_NETWORK_SIZE).boxed().collect(toSet());
        assertEquals(classicIds, entries.stream().map(RosterEntry::nodeId).collect(toSet()), "Wrong ids");
    }

    private static String classicFeeCollectorIdLiteralFor(final long nodeId) {
        return String.format("%s.%s.%d", SHARD, REALM, (nodeId + 3L));
    }
}
