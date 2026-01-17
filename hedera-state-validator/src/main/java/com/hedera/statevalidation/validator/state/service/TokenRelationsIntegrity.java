// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.state.service;

import static com.hedera.statevalidation.util.ParallelProcessingUtils.VALIDATOR_FORK_JOIN_POOL;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.StateResolver;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.state.merkle.StateValue;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapMigration;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, SlackReportGenerator.class})
@Tag("tokenRelations")
public class TokenRelationsIntegrity {

    private static final Logger log = LogManager.getLogger(TokenRelationsIntegrity.class);

    @Test
    void validate(DeserializedSignedState deserializedState) throws InterruptedException {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();

        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(merkleNodeState.getReadableStates(EntityIdService.NAME));
        final ReadableKVState<AccountID, Account> tokenAccounts =
                merkleNodeState.getReadableStates(TokenService.NAME).get(V0490TokenSchema.ACCOUNTS_STATE_ID);
        final ReadableKVState<TokenID, Token> tokenTokens =
                merkleNodeState.getReadableStates(TokenService.NAME).get(V0490TokenSchema.TOKENS_STATE_ID);

        assertNotNull(entityCounters);
        assertNotNull(tokenAccounts);
        assertNotNull(tokenTokens);

        final long numTokenRelations = entityCounters.numTokenRelations();
        log.debug("Number of token relations: {}", entityCounters.numTokens());
        log.debug("Number of accounts: {}", entityCounters.numAccounts());
        log.debug("Number of token relations: {}", numTokenRelations);

        AtomicInteger objectsProcessed = new AtomicInteger();
        AtomicInteger accountFailCounter = new AtomicInteger(0);
        AtomicInteger tokenFailCounter = new AtomicInteger(0);

        final int tokenRelsStateId = V0490TokenSchema.TOKEN_RELS_STATE_ID;

        InterruptableConsumer<Pair<Bytes, Bytes>> handler = pair -> {
            final Bytes keyBytes = pair.left();
            final Bytes valueBytes = pair.right();
            final int readKeyStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(keyBytes);
            final int readValueStateId = StateValue.extractStateIdFromStateValueOneOf(valueBytes);
            if ((readKeyStateId == tokenRelsStateId) && (readValueStateId == tokenRelsStateId)) {
                try {
                    final com.hedera.hapi.platform.state.StateKey stateKey =
                            com.hedera.hapi.platform.state.StateKey.PROTOBUF.parse(keyBytes);

                    final EntityIDPair entityIDPair = stateKey.key().as();
                    final AccountID accountId1 = entityIDPair.accountId();
                    final TokenID tokenId1 = entityIDPair.tokenId();

                    final com.hedera.hapi.platform.state.StateValue stateValue =
                            com.hedera.hapi.platform.state.StateValue.PROTOBUF.parse(valueBytes);
                    final TokenRelation tokenRelation = stateValue.value().as();
                    final AccountID accountId2 = tokenRelation.accountId();
                    final TokenID tokenId2 = tokenRelation.tokenId();

                    assertNotNull(accountId1);
                    assertNotNull(tokenId1);
                    assertNotNull(accountId2);
                    assertNotNull(tokenId2);

                    assertEquals(accountId1, accountId2);
                    assertEquals(tokenId1, tokenId2);

                    if (!virtualMap.containsKey(
                            getStateKeyForKv(V0490TokenSchema.ACCOUNTS_STATE_ID, accountId1, AccountID.PROTOBUF))) {
                        accountFailCounter.incrementAndGet();
                    }

                    if (!virtualMap.containsKey(
                            getStateKeyForKv(V0490TokenSchema.TOKENS_STATE_ID, tokenId1, TokenID.PROTOBUF))) {
                        tokenFailCounter.incrementAndGet();
                    }
                    objectsProcessed.incrementAndGet();
                } catch (final ParseException e) {
                    throw new RuntimeException("Failed to parse a key", e);
                }
            }
        };

        VirtualMapMigration.extractVirtualMapDataC(
                AdHocThreadManager.getStaticThreadManager(),
                virtualMap,
                handler,
                VALIDATOR_FORK_JOIN_POOL.getParallelism());

        assertEquals(objectsProcessed.get(), numTokenRelations);
        assertEquals(0, accountFailCounter.get());
        assertEquals(0, tokenFailCounter.get());
    }
}
