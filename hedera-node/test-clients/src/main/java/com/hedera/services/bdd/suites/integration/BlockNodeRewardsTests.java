// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.NODE_REWARDS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.registeredNodeDelete;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.mutateSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.selectedItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForBlockPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.SelectedItemsAssertion.SELECTED_ITEMS_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.hapi.node.state.token.NodeActivity;
import com.hedera.hapi.node.state.token.NodeRewards;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.ContextualActionOp;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hedera.services.bdd.spec.utilops.embedded.MutateSingletonOp;
import com.hedera.services.bdd.spec.utilops.streams.assertions.EventualRecordStreamAssertion;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItems;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountAmount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * HapiTests for HIP-1357 block node reward distribution.
 *
 * <p>Covers the full eligibility matrix:
 * <ul>
 *   <li>Inactive node, no registered block node</li>
 *   <li>Inactive node, one registered block node</li>
 *   <li>Inactive node, multiple registered block nodes</li>
 *   <li>Active node, no registered block node</li>
 *   <li>Active node, one registered block node</li>
 *   <li>Active node, multiple registered block nodes</li>
 *   <li>Mixed configuration: inactive, active with no block node, and active with a block node</li>
 * </ul>
 */
@Order(9)
@HapiTestLifecycle
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public final class BlockNodeRewardsTests {
    private static final Logger log = LogManager.getLogger(BlockNodeRewardsTests.class);

    // Account number of the node reward account (0.0.801).
    private static final long NODE_REWARD_ACCOUNT_NUM = 801L;

    private static final long CONSENSUS_NODE_YEARLY_REWARD_USD = 25_000L;

    // Yearly block node reward in USD used in these tests. With {@code numPeriodsToTargetUsd=365} these yields a
    // per-period reward of 100 USD.
    private static final long BLOCK_NODE_YEARLY_REWARD_USD = 36_500L;

    // Minimum per-period reward in USD applied to inactive nodes.
    private static final long MIN_INACTIVE_REWARD_USD = 10L;

    // Number of periods of reward calculation in a year.
    private static final long NUMBER_OF_PERIODS = 365;

    private static final long NODE_0_ID = 0L;
    private static final long NODE_1_ID = 1L;
    private static final long NODE_2_ID = 2L;
    private static final long NODE_3_ID = 3L;
    private static final long BLOCK_NODE_0_INDEX = 0L;
    private static final long BLOCK_NODE_1_INDEX = 1L;

    private static final String NODES_ACTIVE_ROUNDS_PERCENT = "nodes.activeRoundsPercent";
    private static final String NODES_MIN_PER_PERIOD_NODE_REWARD_USD = "nodes.minPerPeriodNodeRewardUsd";
    private static final String BLOCK_NODE = "blockNode";

    /**
     * Set up class-level overrides before all tests in the class are run.
     */
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "nodes.nodeRewardsEnabled", "true",
                "nodes.targetYearlyNodeRewardsUsd", String.valueOf(CONSENSUS_NODE_YEARLY_REWARD_USD),
                "nodes.numPeriodsToTargetUsd", String.valueOf(NUMBER_OF_PERIODS),
                "nodes.targetYearlyBlockNodeRewardsUsd", String.valueOf(BLOCK_NODE_YEARLY_REWARD_USD),
                "nodes.adjustNodeFees", "false",
                "nodes.feeCollectionAccountEnabled", "false",
                "ledger.transfers.maxLen", "10"));
    }

    /**
     * Scenario 1: 3 CNs, no BNs. Only node 2 accepts rewards. Node 2 is inactive though.
     * Expectation: Node 2 receives the minimum per-period reward.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_MIN_PER_PERIOD_NODE_REWARD_USD, NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(1)
    Stream<DynamicTest> inactiveNodeWithNoRegisteredBlockNodeGetsOnlyMinimumReward() {
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(3)
                .numBlockNodes(0)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID)
                .minPerPeriodNodeRewardUsd(MIN_INACTIVE_REWARD_USD)
                .expectTransfersInUSD(
                        Transfer.to(NODE_2_ID).amount(MIN_INACTIVE_REWARD_USD),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-MIN_INACTIVE_REWARD_USD))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 2: 3 CNs, 1 BN. Only node 2 accepts rewards. Node 2 is inactive, though.
     * Expectation: Node 2 receives the minimum per-period reward, as inactive nodes don't get
     * rewards for associated block nodes.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_MIN_PER_PERIOD_NODE_REWARD_USD, NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(2)
    Stream<DynamicTest> inactiveNodeWithOneRegisteredBlockNodeGetsOnlyMinimumReward() {
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(4)
                .numBlockNodes(1)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID, NODE_3_ID)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_0_INDEX)
                .minPerPeriodNodeRewardUsd(MIN_INACTIVE_REWARD_USD)
                .expectTransfersInUSD(
                        Transfer.to(NODE_2_ID).amount(MIN_INACTIVE_REWARD_USD),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-MIN_INACTIVE_REWARD_USD))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 3: 4 CNs, 2 BNs. Only node 2 accepts rewards. Node 2 is inactive, though.
     * Both block nodes are associated with node 2.
     * <p>
     * Expectation: Node 2 receives the minimum per-period reward, as inactive nodes don't get
     * rewards for associated, even if it has more than one associated block node.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_MIN_PER_PERIOD_NODE_REWARD_USD, NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(3)
    Stream<DynamicTest> inactiveNodeWithMultipleRegisteredBlockNodesGetsOnlyMinimumReward() {
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(4)
                .numBlockNodes(2)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID, NODE_3_ID)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_0_INDEX)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_1_INDEX)
                .minPerPeriodNodeRewardUsd(MIN_INACTIVE_REWARD_USD)
                .expectTransfersInUSD(
                        Transfer.to(NODE_2_ID).amount(MIN_INACTIVE_REWARD_USD),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-MIN_INACTIVE_REWARD_USD))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 4: 3 CNs, no BNs. Only node 2 accepts rewards, and it is active.
     * However, node 2 is not associated with any block node.
     * <p>
     * Expectation: Node 2 receives the full per-period reward for active nodes.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_MIN_PER_PERIOD_NODE_REWARD_USD, NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(4)
    Stream<DynamicTest> activeNodeWithNoRegisteredBlockNodeGetsOnlyConsensusReward() {
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(3)
                .numBlockNodes(0)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID)
                .activeNodes(NODE_2_ID)
                .expectTransfersFromYearlyValue(
                        Transfer.to(NODE_2_ID).amount(CONSENSUS_NODE_YEARLY_REWARD_USD),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-CONSENSUS_NODE_YEARLY_REWARD_USD))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 5: 3 CNs, no BNs. Only node 2 accepts rewards, and it is active.
     * Node 2 is associated with one block node.
     * <p>
     * Expectation: Node 2 receives the full per-period reward for active nodes plus
     * the block node reward.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_MIN_PER_PERIOD_NODE_REWARD_USD, NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(5)
    Stream<DynamicTest> activeNodeWithOneRegisteredBlockNodeGetsConsensusAndBlockReward() {
        long expectedYearlyReward = CONSENSUS_NODE_YEARLY_REWARD_USD + BLOCK_NODE_YEARLY_REWARD_USD;

        final var ctx = new TestContext.Builder()
                .numConsensusNodes(3)
                .numBlockNodes(1)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID)
                .activeNodes(NODE_2_ID)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_0_INDEX)
                .expectTransfersFromYearlyValue(
                        Transfer.to(NODE_2_ID).amount(expectedYearlyReward),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-expectedYearlyReward))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 6: 3 CNs and 2 BNs. Only node 2 is active and has 2 BNs associated with it.
     * <p>
     * Expectation: Node 2 receives the full per-period reward for active nodes plus the block node reward.
     * The fact that it has two BNs associated with it should not affect the reward calculation.
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(6)
    Stream<DynamicTest> activeNodeWithMultipleRegisteredBlockNodesGetsConsensusAndBlockReward() {
        long expectedYearlyReward = CONSENSUS_NODE_YEARLY_REWARD_USD + BLOCK_NODE_YEARLY_REWARD_USD;
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(3)
                .numBlockNodes(2)
                .nodesDecliningReward(NODE_0_ID, NODE_1_ID)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_0_INDEX)
                .consensusToBlockNodeAssociations(NODE_2_ID, BLOCK_NODE_1_INDEX)
                .activeNodes(NODE_2_ID)
                .expectTransfersFromYearlyValue(
                        Transfer.to(NODE_2_ID).amount(expectedYearlyReward),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM).amount(-expectedYearlyReward))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Scenario 7: 4 CNs, 2 BNs, with the following configuration:
     *     <ul>
     *         <li>Node 0 declines reward.</li>
     *         <li>Node 1 is inactive, getting just the minimum reward.</li>
     *         <li>Node 2 is active without any block node association.</li>
     *         <li>Node 3 is active and has one block node association.</li>
     *     </ul>
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {NODES_ACTIVE_ROUNDS_PERCENT})
    @Order(7)
    Stream<DynamicTest> mixedNodesConfiguration() {
        final long expectedYearlyRewardConsensusAndBlockNodes =
                CONSENSUS_NODE_YEARLY_REWARD_USD + BLOCK_NODE_YEARLY_REWARD_USD;
        final long expectedYearlyRewardConsensusOnly = CONSENSUS_NODE_YEARLY_REWARD_USD;
        final long expectedYearlyMinReward = MIN_INACTIVE_REWARD_USD * NUMBER_OF_PERIODS;
        final var ctx = new TestContext.Builder()
                .numConsensusNodes(4)
                .numBlockNodes(2)
                .nodesDecliningReward(NODE_0_ID)
                .activeNodes(NODE_2_ID, NODE_3_ID)
                .consensusToBlockNodeAssociations(NODE_3_ID, BLOCK_NODE_0_INDEX)
                .minPerPeriodNodeRewardUsd(MIN_INACTIVE_REWARD_USD)
                .expectTransfersFromYearlyValue(
                        Transfer.to(NODE_1_ID).amount(expectedYearlyMinReward),
                        Transfer.to(NODE_2_ID).amount(expectedYearlyRewardConsensusOnly),
                        Transfer.to(NODE_3_ID).amount(expectedYearlyRewardConsensusAndBlockNodes),
                        Transfer.to(NODE_REWARD_ACCOUNT_NUM)
                                .amount(-(expectedYearlyRewardConsensusAndBlockNodes
                                        + expectedYearlyMinReward
                                        + expectedYearlyRewardConsensusOnly)))
                .build();
        return blockNodeRewardScenario(ctx);
    }

    /**
     * Runs a complete block node reward test scenario using the provided context.
     */
    private static Stream<DynamicTest> blockNodeRewardScenario(@NonNull final TestContext ctx) {
        final List<SpecOperation> ops = new ArrayList<>(setupNodesDecliningRewards(ctx));

        ops.add(overriding(NODES_MIN_PER_PERIOD_NODE_REWARD_USD, String.valueOf(ctx.minPerPeriodNodeRewardUsd)));

        // emulates a complete block to ensure the reward declination is written and we have a clean state for tests.
        ops.add(sleepForBlockPeriod());
        ops.add(EmbeddedVerbs.handleAnyRepeatableQueryPayment());

        // Ensure the node reward account has sufficient funds.
        ops.add(setupRewardAccountInitialBalance());

        // Create registered block nodes and capture their auto-assigned IDs.
        for (int i = 0; i < ctx.numBlockNodes(); i++) {
            ops.add(registeredNodeCreate(BLOCK_NODE + i)
                    .exposingCreatedIdTo(ctx.blockNodeIds().get(i)::set));
        }

        // Associate consensus node Ids with block node Ids.
        for (final var association : ctx.blockNodeAssociations()) {
            ops.add(associateBlockNodes(ctx.blockNodeIds(), association.getKey(), association.getValue()));
        }

        // Snapshot the reward acc balance (used by the validator to cap expected amounts).
        ops.add(getAccountBalance(NODE_REWARD).exposingBalanceTo(ctx.nodeRewardBalance()::set));

        ops.add(sleepForBlockPeriod());
        // This is considered as one transaction submitted, so one round
        ops.add(EmbeddedVerbs.handleAnyRepeatableQueryPayment());

        // configures node activity info based on the configured test context
        ops.add(forceNodeActivity(ctx));

        // Capture consensus time and register the record-stream listener AFTER the forced
        // activity has been flushed, so the filter only matches the intended reward payment.
        ops.add(extractConsensusTime(ctx));
        ops.add(setupRecordStreamListener(ctx));

        // Advance to the next staking period — this triggers the reward payment for the period above.
        ops.add(waitUntilStartOfNextStakingPeriod(1));

        ops.addAll(cleanUp(ctx));

        return hapiTest(ops.toArray(SpecOperation[]::new));
    }

    /**
     * Configures nodes to decline rewards based on the test context.
     */
    private static List<SpecOperation> setupNodesDecliningRewards(@NonNull final TestContext ctx) {
        return List.of(withOpContext((spec, _) -> {
            final var nodes = spec.targetNetworkOrThrow().nodes();
            for (final var node : nodes) {
                final long nodeId = node.getNodeId();
                final boolean shouldDecline =
                        ctx.nodesDecliningRewards().contains(nodeId) || nodeId >= ctx.numConsensusNodes();
                allRunFor(spec, nodeUpdate(String.valueOf(nodeId)).declineReward(shouldDecline));
            }
        }));
    }

    /**
     * Resets state after a test scenario to ensure a clean slate for subsequent tests.
     */
    private static List<SpecOperation> cleanUp(@NonNull final TestContext ctx) {
        final List<SpecOperation> cleanupOps = new ArrayList<>();
        // reset account balance
        cleanupOps.add(cryptoCreate("nobody").payingWith(GENESIS));
        cleanupOps.add(doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted));

        if (ctx.numBlockNodes() > 0) {
            // clean consensus -> block nodes associations
            ctx.blockNodeAssociations()
                    .forEach(association -> cleanupOps.add(withOpContext((spec, _) -> allRunFor(
                            spec,
                            nodeUpdate(String.valueOf(association.getKey())).associatedRegisteredNode(List.of())))));

            // delete created block nodes
            LongStream.range(0, ctx.numBlockNodes()).forEach(i -> cleanupOps.add(registeredNodeDelete(BLOCK_NODE + i)));
        }
        cleanupOps.add(mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, _ -> NodeRewards.DEFAULT));
        return cleanupOps;
    }

    /** Configures node activity levels based on the test context. */
    private static MutateSingletonOp<NodeRewards> forceNodeActivity(@NonNull final TestContext ctx) {
        return mutateSingleton(TokenService.NAME, NODE_REWARDS_STATE_ID, (final NodeRewards nodeRewards) -> {
            final List<NodeActivity> activities = LongStream.range(0, ctx.numConsensusNodes())
                    .mapToObj(nodeId -> {
                        final var isActive = ctx.activeNodes.contains(nodeId);
                        final var totalRounds = 100;
                        final var missed = isActive ? 0 : totalRounds;
                        return NodeActivity.newBuilder()
                                .nodeId(nodeId)
                                .numMissedJudgeRounds(missed)
                                .build();
                    })
                    .collect(toList());
            return nodeRewards
                    .copyBuilder()
                    .numRoundsInStakingPeriod(100)
                    .nodeActivities(activities)
                    .build();
        });
    }

    /** Associates a consensus node with one or more block nodes in the network. */
    private static CustomSpecAssert associateBlockNodes(
            @NonNull final List<AtomicLong> realBlockNodeIds,
            final long consensusNodeId,
            @NonNull final List<Long> blockNodeIndices) {
        return withOpContext((spec, _) -> {
            final var realIds = blockNodeIndices.stream()
                    .map(idx -> realBlockNodeIds.get(idx.intValue()).get())
                    .toList();
            allRunFor(spec, nodeUpdate(String.valueOf(consensusNodeId)).associatedRegisteredNode(realIds));
        });
    }

    /** Ensures the node reward account has enough funds for the tests. */
    private static HapiCryptoTransfer setupRewardAccountInitialBalance() {
        return cryptoTransfer(TokenMovement.movingHbar(ONE_MILLION_HBARS).between(GENESIS, NODE_REWARD));
    }

    /** Captures the consensus time before reward calculation. */
    private static ContextualActionOp extractConsensusTime(@NonNull final TestContext ctx) {
        return doingContextual(spec -> ctx.startConsensusTime().set(spec.consensusTime()));
    }

    /** Configures a record stream listener to capture and validate reward transfers. */
    private static EventualRecordStreamAssertion setupRecordStreamListener(@NonNull final TestContext ctx) {
        return recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                selectedItems(nodeRewardValidator(ctx), 1, filterRewardDebitTransaction(ctx)), Duration.ofSeconds(1));
    }

    /** Returns a validator that compares observed reward transfers against expected values. */
    private static VisibleItemsValidator nodeRewardValidator(@NonNull final TestContext ctx) {
        return (spec, records) -> {
            final Map<Long, Long> transfersByAccount = getTransfersByAccount(records);
            assertEquals(
                    ctx.expectedRewards().size(),
                    transfersByAccount.size(),
                    "Unexpected number of adjustments in transaction. " + "Expected "
                            + ctx.expectedRewards().size() + " but got " + transfersByAccount.size());

            final Map<Long, Long> nodeToAccountNum = nodeToAccountMap(spec);
            for (final var expectedReward : ctx.expectedRewards().entrySet()) {
                final var nodeId = expectedReward.getKey();
                final var accountId = nodeToAccountNum.get(nodeId);
                assertNotNull(accountId, "No account found for node " + nodeId);
                final var expectedRewardTinyUSD = expectedReward.getValue();
                final var expectedRewardTinybars = spec.ratesProvider().toTbWithActiveRates(expectedRewardTinyUSD);
                final var actualRewardTinybars = transfersByAccount.get(accountId);
                // assert with a max delta of 1 tinybar (integer divisions can lead to this)
                assertEquals(
                        expectedRewardTinybars,
                        actualRewardTinybars,
                        1,
                        "Unexpected reward for node " + nodeId + ". Expected " + expectedRewardTinybars + " but got "
                                + actualRewardTinybars);
            }
        };
    }

    /** Aggregates reward transfers by account ID from record stream items. */
    private static Map<Long, Long> getTransfersByAccount(@NonNull final Map<String, VisibleItems> records) {
        final var items = records.get(SELECTED_ITEMS_KEY);
        assertNotNull(items, "No reward payment found in the record stream");
        final var payment = items.getFirst();
        assertEquals(CryptoTransfer, payment.function());

        final var op = payment.body().getCryptoTransfer();
        return op.getTransfers().getAccountAmountsList().stream()
                .collect(toMap(aa -> aa.getAccountID().getAccountNum(), AccountAmount::getAmount));
    }

    /** Maps consensus node IDs to their respective reward account numbers. */
    private static Map<Long, Long> nodeToAccountMap(@NonNull final HapiSpec spec) {
        final Map<Long, Long> nodeToAccountNum = spec.targetNetworkOrThrow().nodes().stream()
                .collect(toMap(HederaNode::getNodeId, n -> n.getAccountId().accountNumOrThrow()));
        nodeToAccountNum.put(NODE_REWARD_ACCOUNT_NUM, NODE_REWARD_ACCOUNT_NUM);
        return nodeToAccountNum;
    }

    /** Provides a filter to identify the transaction that debits the node reward account. */
    private static BiPredicate<HapiSpec, RecordStreamItem> filterRewardDebitTransaction(
            @NonNull final TestContext ctx) {
        return (_, item) -> item.getRecord().getTransferList().getAccountAmountsList().stream()
                        .anyMatch(aa ->
                                aa.getAccountID().getAccountNum() == NODE_REWARD_ACCOUNT_NUM && aa.getAmount() < 0L)
                && asInstant(CommonPbjConverters.toPbj(item.getRecord().getConsensusTimestamp()))
                        .isAfter(ctx.startConsensusTime().get());
    }

    /** A transfer of rewards to a node. */
    record Transfer(long nodeId, long amount) {

        public static Transfer to(long nodeId) {
            return new Transfer(nodeId, 0);
        }

        public Transfer amount(long amount) {
            return new Transfer(nodeId, amount);
        }
    }

    /** Context for the block node reward scenario. */
    private static final class TestContext {

        private final int numConsensusNodes;
        private final AtomicReference<Instant> startConsensusTime = new AtomicReference<>();
        private final AtomicLong nodeRewardBalance = new AtomicLong(0);
        private final List<AtomicLong> generatedBlockNodeIds = new ArrayList<>();
        private final Set<Long> nodesDecliningRewards = new HashSet<>();
        private final int numBlockNodes;
        private final Set<Long> activeNodes = new HashSet<>();
        private final Map<Long, List<Long>> consensusToBlockNodeAssociations = new LinkedHashMap<>();
        private final long minPerPeriodNodeRewardUsd;
        private final Map<Long, Long> expectedRewards = new LinkedHashMap<>();

        /** Private constructor for {@link TestContext}. */
        private TestContext(
                final int numConsensusNodes,
                final int numBlockNodes,
                @NonNull final Set<Long> nodesDecliningRewards,
                @NonNull final Set<Long> activeNodes,
                @NonNull final Map<Long, List<Long>> consensusToBlockNodeAssociations,
                final long minPerPeriodNodeRewardUsd,
                @NonNull final Map<Long, Long> expectedRewards) {
            this.numConsensusNodes = numConsensusNodes;
            this.numBlockNodes = numBlockNodes;
            for (int i = 0; i < numBlockNodes; i++) {
                generatedBlockNodeIds.add(new AtomicLong(-1));
            }
            this.nodesDecliningRewards.addAll(nodesDecliningRewards);
            this.activeNodes.addAll(activeNodes);
            this.consensusToBlockNodeAssociations.putAll(consensusToBlockNodeAssociations);
            this.minPerPeriodNodeRewardUsd = minPerPeriodNodeRewardUsd;
            this.expectedRewards.putAll(expectedRewards);
        }

        private List<AtomicLong> blockNodeIds() {
            return Collections.unmodifiableList(generatedBlockNodeIds);
        }

        private int numConsensusNodes() {
            return numConsensusNodes;
        }

        private int numBlockNodes() {
            return numBlockNodes;
        }

        private AtomicReference<Instant> startConsensusTime() {
            return startConsensusTime;
        }

        private Map<Long, Long> expectedRewards() {
            return Collections.unmodifiableMap(expectedRewards);
        }

        private AtomicLong nodeRewardBalance() {
            return nodeRewardBalance;
        }

        private Set<Long> nodesDecliningRewards() {
            return Collections.unmodifiableSet(nodesDecliningRewards);
        }

        private Set<Entry<Long, List<Long>>> blockNodeAssociations() {
            return Collections.unmodifiableSet(consensusToBlockNodeAssociations.entrySet());
        }

        /** A builder for the {@link TestContext} record. */
        private static final class Builder {

            private int numConsensusNodes = 0;
            private int numBlockNodes = 0;
            private final Set<Long> activeNodes = new LinkedHashSet<>();
            private final Set<Long> nodesDecliningRewards = new LinkedHashSet<>();
            private final Map<Long, List<Long>> consensusToBlockNodeAssociations = new LinkedHashMap<>();
            private long minPerPeriodNodeRewardUsd = 0L;
            private final Map<Long, Long> expectedRewards = new LinkedHashMap<>();

            public Builder numConsensusNodes(final int numConsensusNodes) {
                this.numConsensusNodes = numConsensusNodes;
                return this;
            }

            public Builder numBlockNodes(final int numBlockNodes) {
                this.numBlockNodes = numBlockNodes;
                return this;
            }

            public Builder nodesDecliningReward(final long... nodeIds) {
                for (final long nodeId : nodeIds) {
                    nodesDecliningRewards.add(nodeId);
                }
                return this;
            }

            public Builder activeNodes(final long... nodeIds) {
                for (final long nodeId : nodeIds) {
                    activeNodes.add(nodeId);
                }
                return this;
            }

            public Builder consensusToBlockNodeAssociations(
                    @NonNull final Long consensusNodeId, @NonNull final Long blockNodeIndex) {
                consensusToBlockNodeAssociations
                        .computeIfAbsent(consensusNodeId, _ -> new ArrayList<>())
                        .add(blockNodeIndex);
                return this;
            }

            public Builder minPerPeriodNodeRewardUsd(final long minPerPeriodNodeRewardUsd) {
                this.minPerPeriodNodeRewardUsd = minPerPeriodNodeRewardUsd;
                return this;
            }

            public Builder expectTransfersInUSD(@NonNull final Transfer... transfers) {
                for (final var transfer : transfers) {
                    this.expectedRewards.put(transfer.nodeId(), toTinyDollarsInPeriod(transfer.amount(), 1));
                }
                return this;
            }

            public Builder expectTransfersFromYearlyValue(@NonNull final Transfer... transfers) {
                for (final var transfer : transfers) {
                    this.expectedRewards.put(
                            transfer.nodeId(), toTinyDollarsInPeriod(transfer.amount(), NUMBER_OF_PERIODS));
                }
                return this;
            }

            public TestContext build() {
                return new TestContext(
                        numConsensusNodes,
                        numBlockNodes,
                        nodesDecliningRewards,
                        activeNodes,
                        consensusToBlockNodeAssociations,
                        minPerPeriodNodeRewardUsd,
                        expectedRewards);
            }

            /** Converts USD to tinybars per period. */
            private static long toTinyDollarsInPeriod(final long usd, final long period) {
                return BigInteger.valueOf(usd)
                        .multiply(BigInteger.valueOf(TINY_PARTS_PER_WHOLE))
                        .multiply(BigInteger.valueOf(100))
                        .divide(BigInteger.valueOf(period))
                        .longValue();
            }
        }
    }
}
