// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.AdapterUtils.mockStates;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.util.TokenRelListCalculator;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenRelListCalculatorTest {
    private TokenRelListCalculator subject;

    @BeforeEach
    void setup() {
        subject = new TokenRelListCalculator(localTokenRelsStore());
    }

    private static final TokenID TOKEN_ID_1 = asToken(1L);
    private static final TokenID TOKEN_ID_2 = asToken(2L);
    private static final TokenID TOKEN_ID_3 = asToken(3L);
    private static final TokenID TOKEN_ID_4 = asToken(4L);
    private static final TokenID TOKEN_ID_5 = asToken(5L);

    private static final AccountID ACCT_2300_ID =
            AccountID.newBuilder().accountNum(2300L).build();
    private static final Account ACCT_2300 = Account.newBuilder()
            .accountId(ACCT_2300_ID)
            .headTokenId(TOKEN_ID_1)
            .numberAssociations(5)
            .build();

    // We construct a test dataset of "local" token relations for the test account 2300, where token relation (account
    // 2300, token 1) <-> (account 2300, token 2) <-> ... (account 2300, token N). In other words, (account 2300, token
    // 1).prevToken() = -1, (account 2300, token 1).nextToken() = 2, (account 2300, token 2).prevToken() = 1, (account
    // 2300, token 2).nextToken() = 3, etc.
    private static final TokenRelation LOCAL_TOKEN_REL_1 = TokenRelation.newBuilder()
            .accountId(ACCT_2300_ID)
            .tokenId(TOKEN_ID_1)
            .previousToken((TokenID) null)
            .nextToken(TOKEN_ID_2)
            .build();
    private static final TokenRelation LOCAL_TOKEN_REL_2 = TokenRelation.newBuilder()
            .accountId(ACCT_2300_ID)
            .tokenId(TOKEN_ID_2)
            .previousToken(TOKEN_ID_1)
            .nextToken(TOKEN_ID_3)
            .build();
    private static final TokenRelation LOCAL_TOKEN_REL_3 = TokenRelation.newBuilder()
            .accountId(ACCT_2300_ID)
            .tokenId(TOKEN_ID_3)
            .previousToken(TOKEN_ID_2)
            .nextToken(TOKEN_ID_4)
            .build();
    private static final TokenRelation LOCAL_TOKEN_REL_4 = TokenRelation.newBuilder()
            .accountId(ACCT_2300_ID)
            .tokenId(TOKEN_ID_4)
            .previousToken(TOKEN_ID_3)
            .nextToken(TOKEN_ID_5)
            .build();
    private static final TokenRelation LOCAL_TOKEN_REL_5 = TokenRelation.newBuilder()
            .accountId(ACCT_2300_ID)
            .tokenId(TOKEN_ID_5)
            .previousToken(TOKEN_ID_4)
            .nextToken((TokenID) null)
            .build();

    @SuppressWarnings("DataFlowIssue")
    @Test
    void removeTokenRels_nullArgs() {
        final var nonNullTokenRelList = List.of(LOCAL_TOKEN_REL_1);

        Assertions.assertThatThrownBy(() -> subject.removeTokenRels(null, nonNullTokenRelList))
                .isInstanceOf(NullPointerException.class);

        Assertions.assertThatThrownBy(() -> subject.removeTokenRels(mock(Account.class), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void removeTokenRels_emptyTokenRels() {
        final var result = subject.removeTokenRels(ACCT_2300, Collections.emptyList());
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(TOKEN_ID_1);
        Assertions.assertThat(result.updatedTokenRelsStillInChain()).isEmpty();
    }

    @Test
    void removeTokenRels_tokenRelsFromDifferentAccountPresent() {
        final var tokenRelFromDifferentAccount = TokenRelation.newBuilder()
                .accountId(asAccount(0L, 0L, 2301L))
                .tokenId(TOKEN_ID_1)
                .build();
        final var tokenRelsToRemove = List.of(LOCAL_TOKEN_REL_1, LOCAL_TOKEN_REL_2, tokenRelFromDifferentAccount);

        Assertions.assertThatThrownBy(() -> subject.removeTokenRels(ACCT_2300, tokenRelsToRemove))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeTokenRels_removesAllTokenRels() {
        final var allLocalTokenRels =
                List.of(LOCAL_TOKEN_REL_1, LOCAL_TOKEN_REL_2, LOCAL_TOKEN_REL_3, LOCAL_TOKEN_REL_4, LOCAL_TOKEN_REL_5);
        final var result = subject.removeTokenRels(ACCT_2300, allLocalTokenRels);
        Assertions.assertThat(result.updatedHeadTokenId()).isNull();
        Assertions.assertThat(result.updatedTokenRelsStillInChain()).isEmpty();
    }

    @Test
    void removeTokenRels_removesHeadTokenRel() {
        final var onlyLocalHeadTokenRel = List.of(LOCAL_TOKEN_REL_1);
        final var result = subject.removeTokenRels(ACCT_2300, onlyLocalHeadTokenRel);
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(TOKEN_ID_2);
        // Note: we don't need to update LOCAL_TOKEN_REL_3, _4, or _5 because their positions in the token rel list are
        // unchanged
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactly(LOCAL_TOKEN_REL_2
                        .copyBuilder()
                        .previousToken((TokenID) null)
                        .build());
    }

    @Test
    void removeTokenRels_removesEvenTokenRels() {
        final var evenLocalTokenRels = List.of(LOCAL_TOKEN_REL_2, LOCAL_TOKEN_REL_4);
        final var result = subject.removeTokenRels(ACCT_2300, evenLocalTokenRels);
        // The account's head token number shouldn't have changed because it's an odd-numbered token
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(ACCT_2300.headTokenId());
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactlyInAnyOrder(
                        LOCAL_TOKEN_REL_1.copyBuilder().nextToken(TOKEN_ID_3).build(),
                        LOCAL_TOKEN_REL_3
                                .copyBuilder()
                                .previousToken(TOKEN_ID_1)
                                .nextToken(TOKEN_ID_5)
                                .build(),
                        LOCAL_TOKEN_REL_5
                                .copyBuilder()
                                .previousToken(TOKEN_ID_3)
                                .nextToken((TokenID) null)
                                .build());
    }

    @Test
    void removeTokenRels_removesOddTokenRels() {
        final var oddHeadTokenRels = List.of(LOCAL_TOKEN_REL_1, LOCAL_TOKEN_REL_3, LOCAL_TOKEN_REL_5);
        final var result = subject.removeTokenRels(ACCT_2300, oddHeadTokenRels);
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(TOKEN_ID_2);
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactlyInAnyOrder(
                        LOCAL_TOKEN_REL_2
                                .copyBuilder()
                                .previousToken((TokenID) null)
                                .nextToken(TOKEN_ID_4)
                                .build(),
                        LOCAL_TOKEN_REL_4
                                .copyBuilder()
                                .previousToken(TOKEN_ID_2)
                                .nextToken((TokenID) null)
                                .build());
    }

    @Test
    void removeTokenRels_removesConsecutiveTokenRels() {
        final var consecutiveLocalTokenRels = List.of(LOCAL_TOKEN_REL_2, LOCAL_TOKEN_REL_3, LOCAL_TOKEN_REL_4);
        final var result = subject.removeTokenRels(ACCT_2300, consecutiveLocalTokenRels);
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(ACCT_2300.headTokenId());
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactlyInAnyOrder(
                        LOCAL_TOKEN_REL_1.copyBuilder().nextToken(TOKEN_ID_5).build(),
                        LOCAL_TOKEN_REL_5
                                .copyBuilder()
                                .previousToken(TOKEN_ID_1)
                                .build());
    }

    @Test
    void removeTokenRels_removesConsecutiveAndSeparatedTokenRels() {
        // Token rel 1 and token rel 2 are in consecutive order; token rel 4 is separated from the consecutive token
        // rels by token rel 3, which token rel 3 will remain in the list
        final var localTokenRels = List.of(LOCAL_TOKEN_REL_1, LOCAL_TOKEN_REL_2, LOCAL_TOKEN_REL_4);
        final var result = subject.removeTokenRels(ACCT_2300, localTokenRels);
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(TOKEN_ID_3);
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactlyInAnyOrder(
                        LOCAL_TOKEN_REL_3
                                .copyBuilder()
                                .previousToken((TokenID) null)
                                .nextToken(TOKEN_ID_5)
                                .build(),
                        LOCAL_TOKEN_REL_5
                                .copyBuilder()
                                .previousToken(TOKEN_ID_3)
                                .build());
    }

    @Test
    void removeTokenRels_nullAndDuplicateTokenRelsRemoved() {
        final var nullsAndDuplicatesTokenRels = new ArrayList<TokenRelation>();
        nullsAndDuplicatesTokenRels.add(null);
        nullsAndDuplicatesTokenRels.add(null);
        nullsAndDuplicatesTokenRels.add(LOCAL_TOKEN_REL_2);
        nullsAndDuplicatesTokenRels.add(LOCAL_TOKEN_REL_2);
        nullsAndDuplicatesTokenRels.add(LOCAL_TOKEN_REL_4);
        nullsAndDuplicatesTokenRels.add(null);
        nullsAndDuplicatesTokenRels.add(LOCAL_TOKEN_REL_4);
        nullsAndDuplicatesTokenRels.add(null);
        nullsAndDuplicatesTokenRels.add(LOCAL_TOKEN_REL_2);
        final var result = subject.removeTokenRels(ACCT_2300, nullsAndDuplicatesTokenRels);

        // Results should be identical to the _removesEvenTokenRels case

        // The account's head token number shouldn't have changed because it's an odd-numbered token
        Assertions.assertThat(result.updatedHeadTokenId()).isEqualTo(ACCT_2300.headTokenId());
        Assertions.assertThat(result.updatedTokenRelsStillInChain())
                .containsExactlyInAnyOrder(
                        LOCAL_TOKEN_REL_1.copyBuilder().nextToken(TOKEN_ID_3).build(),
                        LOCAL_TOKEN_REL_3
                                .copyBuilder()
                                .previousToken(TOKEN_ID_1)
                                .nextToken(TOKEN_ID_5)
                                .build(),
                        LOCAL_TOKEN_REL_5
                                .copyBuilder()
                                .previousToken(TOKEN_ID_3)
                                .nextToken((TokenID) null)
                                .build());
    }

    @Test
    void removeTokenRels_selfPointingTokenRel() {
        final var selfPointingTokenRel = LOCAL_TOKEN_REL_1
                .copyBuilder()
                .previousToken(TOKEN_ID_1)
                .nextToken(TOKEN_ID_1)
                .build();
        final var result = subject.removeTokenRels(ACCT_2300, List.of(selfPointingTokenRel));
        // Since the token rel points to itself, the calculation of the account's new head token number should loop
        // until it maxes out at a safety boundary, at which point we should default to a head token number of -1
        Assertions.assertThat(result.updatedHeadTokenId()).isNull();
    }

    private static ReadableTokenRelationStore localTokenRelsStore() {
        final var tokenRels = new HashMap<EntityIDPair, TokenRelation>();
        tokenRels.put(
                EntityIDPair.newBuilder()
                        .accountId(ACCT_2300_ID)
                        .tokenId(TOKEN_ID_1)
                        .build(),
                LOCAL_TOKEN_REL_1);
        tokenRels.put(
                EntityIDPair.newBuilder()
                        .accountId(ACCT_2300_ID)
                        .tokenId(TOKEN_ID_2)
                        .build(),
                LOCAL_TOKEN_REL_2);
        tokenRels.put(
                EntityIDPair.newBuilder()
                        .accountId(ACCT_2300_ID)
                        .tokenId(TOKEN_ID_3)
                        .build(),
                LOCAL_TOKEN_REL_3);
        tokenRels.put(
                EntityIDPair.newBuilder()
                        .accountId(ACCT_2300_ID)
                        .tokenId(TOKEN_ID_4)
                        .build(),
                LOCAL_TOKEN_REL_4);
        tokenRels.put(
                EntityIDPair.newBuilder()
                        .accountId(ACCT_2300_ID)
                        .tokenId(TOKEN_ID_5)
                        .build(),
                LOCAL_TOKEN_REL_5);

        final var wrappedState = new MapReadableKVState<>(TokenService.NAME, TOKEN_RELS_KEY, tokenRels);
        return new ReadableTokenRelationStoreImpl(
                mockStates(Map.of(TOKEN_RELS_KEY, wrappedState)), mock(ReadableEntityCounters.class));
    }
}
