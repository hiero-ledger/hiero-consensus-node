// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.servicesstate;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.VALIDATOR_FORK_JOIN_POOL;
import static com.swirlds.state.merkle.StateUtils.extractVirtualMapKeyValueStateId;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.platform.state.VirtualMapKey;
import com.hedera.hapi.platform.state.VirtualMapValue;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
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

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("tokenRelations")
public class TokenRelationsIntegrity {

    private static final Logger log = LogManager.getLogger(TokenRelationsIntegrity.class);

    @Test
    void validate(DeserializedSignedState deserializedState, Report report) throws InterruptedException {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();

        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);

        final ReadableEntityIdStore entityCounters =
                new ReadableEntityIdStoreImpl(merkleNodeState.getReadableStates(EntityIdService.NAME));
        final ReadableKVState<AccountID, Account> tokenAccounts =
                merkleNodeState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_KEY);
        final ReadableKVState<TokenID, Token> tokenTokens =
                merkleNodeState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.TOKENS_KEY);

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

        final int targetStateId = stateIdFor(TokenServiceImpl.NAME, V0490TokenSchema.TOKEN_RELS_KEY);

        InterruptableConsumer<Pair<Bytes, Bytes>> handler = pair -> {
            final Bytes keyBytes = pair.left();
            final Bytes valueBytes = pair.right();
            final int readKeyStateId = extractVirtualMapKeyValueStateId(keyBytes);
            final int readValueStateId = extractVirtualMapKeyValueStateId(valueBytes);
            if ((readKeyStateId == targetStateId) && (readValueStateId == targetStateId)) {
                try {
                    final VirtualMapKey parse = VirtualMapKey.PROTOBUF.parse(keyBytes);

                    final EntityIDPair entityIDPair = parse.key().as();
                    final AccountID accountId1 = entityIDPair.accountId();
                    final TokenID tokenId1 = entityIDPair.tokenId();

                    final VirtualMapValue virtualMapValue = VirtualMapValue.PROTOBUF.parse(valueBytes);
                    final TokenRelation tokenRelation = virtualMapValue.value().as();
                    final AccountID accountId2 = tokenRelation.accountId();
                    final TokenID tokenId2 = tokenRelation.tokenId();

                    assertNotNull(accountId1);
                    assertNotNull(tokenId1);
                    assertNotNull(accountId2);
                    assertNotNull(tokenId2);

                    assertEquals(accountId1, accountId2);
                    assertEquals(tokenId1, tokenId2);

                    if (!tokenAccounts.contains(accountId1)) {
                        accountFailCounter.incrementAndGet();
                    }

                    if (!tokenTokens.contains(tokenId1)) {
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
