// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.servicesstate;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.VALIDATOR_FORK_JOIN_POOL;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskValue;
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
        final MerkleStateRoot servicesState =
                (MerkleStateRoot) deserializedState.reservedSignedState().get().getState();

        VirtualMap<OnDiskKey<EntityIDPair>, OnDiskValue<TokenRelation>> tokenRelsVm = null;

        for (int i = 0; i < servicesState.getNumberOfChildren(); i++) {
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap
                    && virtualMap.getLabel().equals("TokenService.TOKEN_RELS")) {
                tokenRelsVm = (VirtualMap<OnDiskKey<EntityIDPair>, OnDiskValue<TokenRelation>>) virtualMap;
            }
        }

        assertNotNull(tokenRelsVm);
        log.debug("TokenService.TOKEN_RELS VM Size: {}", tokenRelsVm.size());

        final ReadableKVState<AccountID, Account> tokenAccounts =
                servicesState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.ACCOUNTS_KEY);
        final ReadableKVState<TokenID, Token> tokenTokens =
                servicesState.getReadableStates(TokenServiceImpl.NAME).get(V0490TokenSchema.TOKENS_KEY);

        assertNotNull(tokenAccounts);
        assertNotNull(tokenTokens);
        log.debug("TokenService.TOKEN_ACCOUNTS Size: {}", tokenAccounts.size());
        log.debug("TokenService.TOKENS Size: {}", tokenTokens.size());

        AtomicInteger objectsProcessed = new AtomicInteger();
        AtomicInteger accountFailCounter = new AtomicInteger(0);
        AtomicInteger tokenFailCounter = new AtomicInteger(0);

        /*
         * Instead of using the State API to iterate through TokenService.TOKEN_RELS, we still use the Virtual Map
         * as it is much faster to iterate over the Virtual Map using `VirtualMapMigration.extractVirtualMapDataC`
         * than to iterate through the ReadableKVState.
         */
        InterruptableConsumer<Pair<OnDiskKey<EntityIDPair>, OnDiskValue<TokenRelation>>> handler = pair -> {
            AccountID keyAccountId = pair.key().getKey().accountId();
            TokenID keyTokenId = pair.key().getKey().tokenId();
            AccountID valueAccountId = pair.value().getValue().accountId();
            TokenID valueTokenId = pair.value().getValue().tokenId();

            assertNotNull(keyAccountId);
            assertNotNull(keyTokenId);
            assertNotNull(valueAccountId);
            assertNotNull(valueTokenId);

            assertEquals(keyAccountId, valueAccountId);
            assertEquals(keyTokenId, valueTokenId);

            if (!tokenAccounts.contains(keyAccountId)) {
                accountFailCounter.incrementAndGet();
            }

            if (!tokenTokens.contains(keyTokenId)) {
                tokenFailCounter.incrementAndGet();
            }
            objectsProcessed.incrementAndGet();
        };

        VirtualMapMigration.extractVirtualMapDataC(
                AdHocThreadManager.getStaticThreadManager(),
                tokenRelsVm,
                handler,
                VALIDATOR_FORK_JOIN_POOL.getParallelism());

        assertEquals(objectsProcessed.get(), tokenRelsVm.size());
        assertEquals(0, accountFailCounter.get());
        assertEquals(0, tokenFailCounter.get());
    }
}
