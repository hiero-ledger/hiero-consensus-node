// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.swirlds.state.spi.WritableKVStateBase;
import com.swirlds.state.spi.WritableStates;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableTokenRelationStoreTest extends CryptoTokenHandlerTestBase {

    private static final long TOKEN_10 = 10L;
    private static final TokenID TOKEN_10_ID =
            TokenID.newBuilder().tokenNum(TOKEN_10).build();
    private static final long ACCOUNT_20 = 20L;
    private static final AccountID ACCOUNT_20_ID =
            AccountID.newBuilder().accountNum(ACCOUNT_20).build();

    @Mock
    private WritableStates states;

    @Mock
    private WritableKVStateBase<EntityIDPair, TokenRelation> tokenRelState;

    private WritableTokenRelationStore subject;

    @BeforeEach
    void setup() {
        given(states.<EntityIDPair, TokenRelation>get(V0490TokenSchema.TOKEN_RELS_STATE_ID))
                .willReturn(tokenRelState);

        subject = new WritableTokenRelationStore(states, writableEntityCounters);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testNullConstructorArgs() {
        assertThrows(NullPointerException.class, () -> new WritableTokenRelationStore(null, writableEntityCounters));
        assertThrows(NullPointerException.class, () -> new WritableTokenRelationStore(states, null));
    }

    @Test
    void testPut() {
        final var expectedTokenRel = TokenRelation.newBuilder()
                .accountId(ACCOUNT_20_ID)
                .tokenId(TOKEN_10_ID)
                .build();

        subject.put(expectedTokenRel);
        verify(tokenRelState)
                .put(
                        EntityIDPair.newBuilder()
                                .accountId(ACCOUNT_20_ID)
                                .tokenId(TOKEN_10_ID)
                                .build(),
                        expectedTokenRel);
    }

    @Test
    void testPutNull() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> subject.put(null));
    }

    @Test
    void testGet() {
        final var tokenRelation = TokenRelation.newBuilder()
                .tokenId(TOKEN_10_ID)
                .accountId(ACCOUNT_20_ID)
                .build();
        given(tokenRelState.get(EntityIDPair.newBuilder()
                        .accountId(ACCOUNT_20_ID)
                        .tokenId(TOKEN_10_ID)
                        .build()))
                .willReturn(tokenRelation);

        final var result = subject.get(ACCOUNT_20_ID, TOKEN_10_ID);
        Assertions.assertThat(result).isEqualTo(tokenRelation);
    }

    @Test
    void testGetEmpty() {
        given(tokenRelState.get(notNull())).willReturn(null);

        final var result =
                subject.get(ACCOUNT_20_ID, TokenID.newBuilder().tokenNum(-1L).build());
        Assertions.assertThat(result).isNull();
    }

    @Test
    void testSizeOfState() {
        Assertions.assertThat(readableEntityCounters.numTokenRelations()).isEqualTo(subject.sizeOfState());
    }

    @Test
    void testModifiedTokens() {
        final var modifiedKeys = Set.of(
                EntityIDPair.newBuilder()
                        .accountId(ACCOUNT_20_ID)
                        .tokenId(TOKEN_10_ID)
                        .build(),
                EntityIDPair.newBuilder()
                        .accountId(asAccount(0L, 0L, 1L))
                        .tokenId(asToken(2L))
                        .build());
        given(tokenRelState.modifiedKeys()).willReturn(modifiedKeys);

        final var result = subject.modifiedTokens();
        Assertions.assertThat(result).isEqualTo(modifiedKeys);
    }
}
