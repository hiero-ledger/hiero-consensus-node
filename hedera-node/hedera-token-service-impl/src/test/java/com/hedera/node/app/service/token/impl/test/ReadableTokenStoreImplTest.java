// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test;

import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableTokenStoreImplTest extends TokenHandlerTestBase {

    @Mock
    private ReadableKVState<TokenID, Token> tokens;

    private final TokenID tokenId = TokenID.newBuilder().tokenNum(2000).build();

    @Mock
    private ReadableStates states;

    private Token token;

    private ReadableTokenStoreImpl subject;

    @BeforeEach
    public void setUp() {
        initializeToken();
        subject = new ReadableTokenStoreImpl(states, readableEntityCounters);
    }

    private void initializeToken() {
        given(states.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(tokens);
        token = createToken();
    }

    @Test
    void getsMerkleTokenIfTokenIdPresent() {
        given(tokens.get(tokenId)).willReturn(token);

        final var meta = subject.getTokenMeta(tokenId);
        assertEquals(adminKey, meta.adminKey());
        assertEquals(kycKey, meta.kycKey());
        assertEquals(wipeKey, meta.wipeKey());
        assertEquals(freezeKey, meta.freezeKey());
        assertEquals(supplyKey, meta.supplyKey());
        assertEquals(feeScheduleKey, meta.feeScheduleKey());
        assertEquals(pauseKey, meta.pauseKey());
        assertTrue(meta.hasRoyaltyWithFallback());
        assertEquals(treasury, meta.treasuryAccountId());
    }

    @Test
    void getsNullKeyIfMissingAccount() {
        given(tokens.get(tokenId)).willReturn(null);
        assertNull(subject.getTokenMeta(tokenId));
    }

    @Test
    void classifiesRoyaltyWithFallback() {
        final var copy = token.copyBuilder();
        copy.tokenType(NON_FUNGIBLE_UNIQUE);
        copy.customFees(CustomFee.newBuilder()
                .royaltyFee(RoyaltyFee.newBuilder()
                        .exchangeValueFraction(Fraction.newBuilder()
                                .numerator(1)
                                .denominator(2)
                                .build())
                        .fallbackFee(FixedFee.newBuilder().amount(1).build())
                        .build())
                .feeCollectorAccountId(AccountID.newBuilder().accountNum(3).build())
                .build());

        given(tokens.get(tokenId)).willReturn(copy.build());

        final var meta = subject.getTokenMeta(tokenId);

        assertTrue(meta.hasRoyaltyWithFallback());
        assertEquals(treasury, meta.treasuryAccountId());
    }

    @Test
    void classifiesRoyaltyWithNoFallback() {
        final var copy = token.copyBuilder();
        copy.tokenType(NON_FUNGIBLE_UNIQUE);
        copy.customFees(CustomFee.newBuilder()
                .royaltyFee(RoyaltyFee.newBuilder()
                        .exchangeValueFraction(Fraction.newBuilder()
                                .numerator(1)
                                .denominator(2)
                                .build())
                        .build())
                .feeCollectorAccountId(AccountID.newBuilder().accountNum(5).build())
                .build());

        given(tokens.get(tokenId)).willReturn(copy.build());

        final var meta = subject.getTokenMeta(tokenId);

        assertFalse(meta.hasRoyaltyWithFallback());
        assertEquals(treasury, meta.treasuryAccountId());
    }

    @Test
    void returnSizeOfState() {
        final var store = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);
        assertEquals(readableEntityCounters.getCounterFor(EntityType.TOKEN), store.sizeOfState());
    }

    @Test
    void warmWarmsUnderlyingState(@Mock ReadableKVState<EntityIDPair, TokenRelation> tokenRelations) {
        subject.warm(tokenId);
        verify(tokens).warm(tokenId);
    }
}
