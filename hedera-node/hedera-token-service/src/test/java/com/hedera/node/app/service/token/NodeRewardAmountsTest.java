// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright (C) 2025 The Hiero Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token;

import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import java.util.ArrayList;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NodeRewardAmounts}. */
class NodeRewardAmountsTest {
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(800L).build();
    private static final AccountID NODE_1_ACCOUNT =
            AccountID.newBuilder().accountNum(1001L).build();
    private static final AccountID NODE_2_ACCOUNT =
            AccountID.newBuilder().accountNum(1002L).build();
    private static final AccountID NODE_3_ACCOUNT =
            AccountID.newBuilder().accountNum(1003L).build();

    private NodeRewardAmounts rewards;

    @BeforeEach
    void setUp() {
        rewards = new NodeRewardAmounts(PAYER_ID);
    }

    @Test
    void constructorThrowsNpeForNullPayerId() {
        assertThrows(NullPointerException.class, () -> new NodeRewardAmounts(null));
    }

    @Test
    void emptyRewardsReturnsTrue() {
        assertTrue(rewards.isEmpty());
        assertEquals(0L, rewards.activeTotalAmount());
        assertEquals(0L, rewards.inactiveTotalAmount());
        assertEquals(0L, rewards.totalAmount());
    }

    @Test
    void emptyRewardsProducesEmptyTransferList() {
        final var transferList = rewards.toTransferList();
        assertTrue(transferList.accountAmounts().isEmpty());
    }

    @Test
    void addConsensusNodeRewardIgnoresZeroAmount() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 0L);
        assertTrue(rewards.isEmpty());
        assertEquals(0L, rewards.totalAmount());
    }

    @Test
    void addConsensusNodeRewardThrowsForNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, -100));
    }

    @Test
    void addBlockNodeRewardIgnoresZeroAmount() {
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 0L);
        assertTrue(rewards.isEmpty());
        assertEquals(0L, rewards.totalAmount());
    }

    @Test
    void addBlockNodeRewardThrowsForNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, -100));
    }

    @Test
    void addInactiveConsensusNodeRewardIgnoresZeroAmount() {
        rewards.addInactiveConsensusNodeReward(1L, NODE_1_ACCOUNT, 0L);
        assertTrue(rewards.isEmpty());
        assertEquals(0L, rewards.totalAmount());
    }

    @Test
    void addInactiveConsensusNodeRewardThrowsForNegativeAmount() {
        assertThrows(
                IllegalArgumentException.class, () -> rewards.addInactiveConsensusNodeReward(1L, NODE_1_ACCOUNT, -100));
    }

    @Test
    void mixedRewardTypesTotalsAreCorrect() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(2L, NODE_2_ACCOUNT, 50L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        // active is consensus + block nodes
        assertEquals(150L, rewards.activeTotalAmount());
        assertEquals(10L, rewards.inactiveTotalAmount());
        assertEquals(160L, rewards.totalAmount());
    }

    @Test
    void onlyActiveNodeRewardsExcludesInactive() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(2L, NODE_2_ACCOUNT, 50L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        final var activeOnly = rewards.onlyActiveNodeRewards();
        assertEquals(150L, activeOnly.totalAmount());
        assertEquals(150L, activeOnly.activeTotalAmount());
        assertEquals(0L, activeOnly.inactiveTotalAmount());
        assertEquals(2, activeOnly.activeNodeCount());
    }

    @Test
    void withCappedInactiveRewardsPartiallyFundsInactive() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addInactiveConsensusNodeReward(2L, NODE_2_ACCOUNT, 10L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        // Budget of 15 for 2 inactive nodes → 15/2 = 7 each
        final var capped = rewards.withCappedInactiveRewards(15);
        assertEquals(100L, capped.activeTotalAmount());
        assertEquals(14L, capped.inactiveTotalAmount()); // 7 * 2
        assertEquals(1, capped.activeNodeCount());
        assertEquals(2, capped.inactiveNodeCount());
    }

    @Test
    void withCappedInactiveRewardsZeroBudgetDropsInactive() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addInactiveConsensusNodeReward(2L, NODE_2_ACCOUNT, 10L);

        final var capped = rewards.withCappedInactiveRewards(0);
        assertEquals(100L, capped.activeTotalAmount());
        assertEquals(0L, capped.inactiveTotalAmount());
        assertEquals(0, capped.inactiveNodeCount());
    }

    @Test
    void withCappedInactiveRewardsSufficientBudgetKeepsOriginalPerNodeAmount() {
        rewards.addInactiveConsensusNodeReward(1L, NODE_1_ACCOUNT, 10L);
        rewards.addInactiveConsensusNodeReward(2L, NODE_2_ACCOUNT, 10L);

        // Budget of 20 is exactly the total → each gets 20/2=10 (same as original)
        final var capped = rewards.withCappedInactiveRewards(20);
        assertEquals(20L, capped.inactiveTotalAmount());
    }

    @Test
    void activeNodeRewardsListExcludesInactiveNodes() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(2L, NODE_2_ACCOUNT, 50L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        final var activeRewardsList = rewards.activeNodeRewards();
        assertEquals(2, activeRewardsList.size());
        assertTrue(activeRewardsList.stream().anyMatch(r -> r.nodeId() == 1L && r.amount() == 100L));
        assertTrue(activeRewardsList.stream().anyMatch(r -> r.nodeId() == 2L && r.amount() == 50L));
        assertFalse(activeRewardsList.stream().anyMatch(r -> r.nodeId() == 3L));
    }

    @Test
    void inactiveNodeCountReturnsCorrectCount() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 50L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 80L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        // node 3 is the only node with exclusively inactive rewards
        assertEquals(1, rewards.inactiveNodeCount());
    }

    @Test
    void activeNodeCountReturnsCorrectCount() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 50L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 80L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        // counts each node just once
        assertEquals(2, rewards.activeNodeCount());
    }

    @Test
    void toTransferListWithSingleActiveNode() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);

        final var transferList = rewards.toTransferList();
        assertEquals(2, transferList.accountAmounts().size());
        assertEquals(100L, amountFor(transferList, NODE_1_ACCOUNT));
        assertEquals(-100L, amountFor(transferList, PAYER_ID));
    }

    @Test
    void toTransferListWithMixedActiveInactive() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 100L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        final var transferList = rewards.toTransferList();
        assertEquals(4, transferList.accountAmounts().size());
        assertEquals(210L, totalCredits(transferList));
        assertEquals(-210L, amountFor(transferList, PAYER_ID));
    }

    @Test
    void toTransferListMergesMultipleRewardTypes() {
        // Node 1 gets both consensus and block rewards
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 50L);

        final var transferList = rewards.toTransferList();
        assertEquals(2, transferList.accountAmounts().size());
        assertEquals(150L, amountFor(transferList, NODE_1_ACCOUNT));
        assertEquals(-150L, amountFor(transferList, PAYER_ID));
    }

    @Test
    void toTransferListPayerDebitEqualsNegativeSumOfCredits() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 50L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 80L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        final var transferList = rewards.toTransferList();
        assertEquals(240L, totalCredits(transferList));
        assertEquals(-240L, totalDebits(transferList));
    }

    @Test
    void complexScenarioWithMultipleNodesAndTypes() {
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 1000L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 1000L);
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 200L);
        rewards.addBlockNodeReward(2L, NODE_2_ACCOUNT, 200L);
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 50L);

        assertEquals(2400L, rewards.activeTotalAmount());
        assertEquals(50L, rewards.inactiveTotalAmount());
        assertEquals(2450L, rewards.totalAmount());

        final var transferList = rewards.toTransferList();
        assertEquals(4, transferList.accountAmounts().size());
        assertEquals(1200L, amountFor(transferList, NODE_1_ACCOUNT));
        assertEquals(1200L, amountFor(transferList, NODE_2_ACCOUNT));
        assertEquals(50L, amountFor(transferList, NODE_3_ACCOUNT));
        assertEquals(-2450L, amountFor(transferList, PAYER_ID));
    }

    @Test
    void toStringIsEmptyWhenNoRewards() {
        assertTrue(rewards.toString().contains("empty"));
    }

    @Test
    void toStringGroupsNodesByRewardBreakdown() {
        // Nodes 1 and 2 share the same consensus-only breakdown → one group
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 100L);
        // Node 1 also gets a block reward → breaks it out of the node-2 group
        rewards.addBlockNodeReward(1L, NODE_1_ACCOUNT, 50L);
        // Node 3 is inactive
        rewards.addInactiveConsensusNodeReward(3L, NODE_3_ACCOUNT, 10L);

        final var output = rewards.toString();

        // Active section present; node 1 (consensus+block) and node 2 (consensus only) are in separate groups
        assertTrue(output.contains("activeNodes"));
        assertTrue(output.contains("Nodes[1]:"));
        assertTrue(output.contains("Nodes[2]:"));
        assertTrue(output.contains("BLOCK_NODE=50"));
        // Inactive section present with node 3
        assertTrue(output.contains("inactiveNodes"));
        assertTrue(output.contains("Nodes[3]:"));
        // Totals summary
        assertTrue(output.contains("totals:"));
        assertTrue(output.contains("active=250"));
        assertTrue(output.contains("inactive=10"));
    }

    @Test
    void toStringCollapsesIdenticalBreakdownsIntoOneGroup() {
        // Nodes 1, 2, 3 all have the same consensus-only reward → one group
        rewards.addConsensusNodeReward(1L, NODE_1_ACCOUNT, 100L);
        rewards.addConsensusNodeReward(2L, NODE_2_ACCOUNT, 100L);
        rewards.addConsensusNodeReward(3L, NODE_3_ACCOUNT, 100L);

        final var output = rewards.toString();

        // All three nodes in one group
        assertTrue(output.contains("Nodes[1, 2, 3]:"));
        // Only one active-nodes entry line (no separate Nodes[1], Nodes[2], Nodes[3] lines)
        // If we have a single entry, the first and last indexes of it shold be the same.
        assertEquals(output.indexOf("CONSENSUS_NODE=100"), output.lastIndexOf("CONSENSUS_NODE=100"));
    }

    /**
     * Verifies that the transfer list generated from NodeRewardAmounts is deterministic,
     * regardless of the order in which rewards are added.
     */
    @Test
    void toTransferListIsDeterministic() {
        final var payerId = AccountID.newBuilder().accountNum(800L).build();

        // Use account IDs that might have different hash orders
        // 1001, 1002, 1003, 1004
        final var account1 = AccountID.newBuilder().accountNum(1001L).build();
        final var account2 = AccountID.newBuilder().accountNum(1002L).build();
        final var account3 = AccountID.newBuilder().accountNum(1003L).build();
        final var account4 = AccountID.newBuilder().accountNum(1004L).build();

        // Create two NodeRewardAmounts with same data but different addition order of DIFFERENT nodes
        // (Insertion into TreeMap will still be sorted by nodeId, so this shouldn't matter for NodeRewardAmounts
        // internal state,
        // but we want to check if the final list is sorted by AccountID)

        final var rewards1 = new NodeRewardAmounts(payerId);
        rewards1.addConsensusNodeReward(1L, account1, 100L);
        rewards1.addConsensusNodeReward(2L, account2, 200L);
        rewards1.addConsensusNodeReward(3L, account3, 300L);
        rewards1.addConsensusNodeReward(4L, account4, 400L);

        final var rewards2 = new NodeRewardAmounts(payerId);
        rewards2.addConsensusNodeReward(4L, account4, 400L);
        rewards2.addConsensusNodeReward(3L, account3, 300L);
        rewards2.addConsensusNodeReward(2L, account2, 200L);
        rewards2.addConsensusNodeReward(1L, account1, 100L);

        final var list1 = rewards1.toTransferList().accountAmounts();
        final var list2 = rewards2.toTransferList().accountAmounts();

        assertEquals(list1, list2, "Transfer lists should be identical regardless of addition order");

        // Also verify they are sorted by AccountID
        final var sortedList = new ArrayList<>(list1);
        sortedList.sort((a, b) -> ACCOUNT_ID_COMPARATOR.compare(a.accountID(), b.accountID()));

        assertEquals(sortedList, list1, "AccountAmounts should be sorted by AccountID for determinism");
    }

    private static long amountFor(final TransferList transferList, final AccountID account) {
        return transferList.accountAmounts().stream()
                .filter(aa -> Objects.equals(aa.accountID(), account))
                .findFirst()
                .orElseThrow()
                .amount();
    }

    private static long totalCredits(final TransferList transferList) {
        return transferList.accountAmounts().stream()
                .filter(aa -> aa.amount() > 0)
                .mapToLong(AccountAmount::amount)
                .sum();
    }

    private static long totalDebits(final TransferList transferList) {
        return transferList.accountAmounts().stream()
                .filter(aa -> aa.amount() < 0)
                .mapToLong(AccountAmount::amount)
                .sum();
    }
}
