// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.translators;

import static com.hedera.node.app.hapi.utils.EntityType.ACCOUNT;
import static com.hedera.node.app.hapi.utils.EntityType.TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.junit.support.translators.inputs.BlockTransactionalUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <a href="https://github.com/hiero-ledger/hiero-consensus-node/issues/20489"></a>
 * Regression tests for {@link BaseTranslator#consumeCreatedNum} that prove the root cause
 * of flaky ContractCreate and ConsensusCreateTopic validation failures.
 *
 * <p>The bug: {@link BaseTranslator#nextCreatedNum} returns numbers in ascending sorted order.
 * When multiple entities of the same type are created in a single transactional unit, and the
 * transactions don't consume them in sorted order (e.g., a child ContractCreate creates entity
 * 1970, but an auto-created account 1946 also exists), the wrong number gets paired with the
 * wrong transaction.
 *
 * <p>The fix: {@link BaseTranslator#consumeCreatedNum} removes a specific number from the list,
 * allowing translators to consume the authoritative entity number (e.g., from the EVM result
 * or state change) instead of blindly taking the first sorted entry.
 */
class BaseTranslatorConsumeCreatedNumTest {

    private static final long SHARD = 0L;
    private static final long REALM = 0L;

    @Test
    @DisplayName("nextCreatedNum returns numbers in sorted order, demonstrating the root cause")
    void nextCreatedNumReturnsSortedOrder() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // Create a unit with two accounts: 1946 (auto-created) and 1970 (contract)
        final var unit = unitWithAccountStateChanges(1946L, 1970L);
        translator.prepareForUnit(unit);

        // nextCreatedNum always returns the LOWEST number first (sorted order)
        // This is the root cause: if the contract transaction runs first and needs 1970,
        // it gets 1946 instead
        assertEquals(
                1946L,
                translator.nextCreatedNum(ACCOUNT),
                "nextCreatedNum returns lowest number first, which may not match the transaction's entity");
        assertEquals(1970L, translator.nextCreatedNum(ACCOUNT));
    }

    @Test
    @DisplayName("consumeCreatedNum removes a specific number regardless of sort position")
    void consumeCreatedNumRemovesSpecificNumber() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // Create a unit with two accounts: 1946 and 1970
        final var unit = unitWithAccountStateChanges(1946L, 1970L);
        translator.prepareForUnit(unit);

        // consumeCreatedNum can remove 1970 directly, even though 1946 is first in sorted order
        assertTrue(
                translator.consumeCreatedNum(ACCOUNT, 1970L), "Should successfully consume the specific number 1970");

        // Now nextCreatedNum returns 1946 (the remaining entry)
        assertEquals(1946L, translator.nextCreatedNum(ACCOUNT), "The other number should still be available");
    }

    @Test
    @DisplayName("consumeCreatedNum returns false for numbers not in the list")
    void consumeCreatedNumReturnsFalseForMissing() {
        final var translator = new BaseTranslator(SHARD, REALM);

        final var unit = unitWithAccountStateChanges(1946L, 1970L);
        translator.prepareForUnit(unit);

        assertFalse(translator.consumeCreatedNum(ACCOUNT, 9999L), "Should return false for a number not in the list");
    }

    @Test
    @DisplayName("Reproduces ContractCreate bug: wrong receipt contractNum due to sorted-order consumption")
    void contractCreateBugReproduction() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // Scenario from the bug: unit has accounts 1946 and 1970 created.
        // ContractCreate's EVM result says the contract is 1970.
        // OLD behavior: nextCreatedNum(ACCOUNT) returns 1946 (wrong!)
        // NEW behavior: consumeCreatedNum(ACCOUNT, 1970) removes the correct entry
        final var unit = unitWithAccountStateChanges(1946L, 1970L);
        translator.prepareForUnit(unit);

        final long contractNumFromEvmResult = 1970L;

        // The fix: consume the specific contractNum from the EVM result
        assertTrue(
                translator.consumeCreatedNum(ACCOUNT, contractNumFromEvmResult),
                "Should consume the correct contract number from the created list");

        // Verify the other entry (1946, an auto-created account) is still available
        // for a subsequent CryptoCreate or other translator
        assertEquals(1946L, translator.nextCreatedNum(ACCOUNT));
    }

    @Test
    @DisplayName("Reproduces ConsensusCreateTopic bug: nextCreatedNum returns wrong topic when multiple topics exist")
    void topicCreateBugReproduction() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // Scenario: unit has topics 6530 and 6536 created.
        // The first TopicCreate transaction actually created topic 6536,
        // but nextCreatedNum(TOPIC) would return 6530.
        final var unit = unitWithTopicStateChanges(6530L, 6536L);
        translator.prepareForUnit(unit);

        final long actualTopicNumFromStateChange = 6536L;

        // The fix: consume the specific topicNum found in the state change
        assertTrue(
                translator.consumeCreatedNum(TOPIC, actualTopicNumFromStateChange),
                "Should consume the specific topic number found in state changes");

        // The other topic (6530) is still available for the next TopicCreate translator
        assertEquals(6530L, translator.nextCreatedNum(TOPIC));
    }

    @Test
    @DisplayName("entityCreatedThisUnit correctly identifies new entities")
    void entityCreatedThisUnitIdentifiesNewEntities() {
        final var translator = new BaseTranslator(SHARD, REALM);

        // First unit creates entity 100
        final var unit1 = unitWithAccountStateChanges(100L);
        translator.prepareForUnit(unit1);
        // Consume to advance state
        translator.nextCreatedNum(ACCOUNT);

        // Second unit creates entity 200
        final var unit2 = unitWithAccountStateChanges(200L);
        translator.prepareForUnit(unit2);

        // 200 was created this unit
        assertTrue(translator.entityCreatedThisUnit(200L));
        // 50 was not created this unit (below previous highest)
        assertFalse(translator.entityCreatedThisUnit(50L));
    }

    // --- Helper methods to construct minimal BlockTransactionalUnit instances ---

    private static BlockTransactionalUnit unitWithAccountStateChanges(long... accountNums) {
        final var stateChanges = new java.util.ArrayList<StateChange>();
        for (final long num : accountNums) {
            stateChanges.add(accountStateChange(num));
        }
        return new BlockTransactionalUnit(List.of(), stateChanges);
    }

    private static BlockTransactionalUnit unitWithTopicStateChanges(long... topicNums) {
        final var stateChanges = new java.util.ArrayList<StateChange>();
        for (final long num : topicNums) {
            stateChanges.add(topicStateChange(num));
        }
        return new BlockTransactionalUnit(List.of(), stateChanges);
    }

    private static StateChange accountStateChange(final long accountNum) {
        final var accountId = AccountID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .accountNum(accountNum)
                .build();
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().accountIdKey(accountId).build())
                        .value(MapChangeValue.newBuilder()
                                .accountValue(Account.newBuilder()
                                        .accountId(accountId)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static StateChange topicStateChange(final long topicNum) {
        return StateChange.newBuilder()
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder()
                                .topicIdKey(TopicID.newBuilder()
                                        .shardNum(SHARD)
                                        .realmNum(REALM)
                                        .topicNum(topicNum)
                                        .build())
                                .build())
                        .value(MapChangeValue.newBuilder()
                                .topicValue(Topic.newBuilder().build())
                                .build())
                        .build())
                .build();
    }
}
