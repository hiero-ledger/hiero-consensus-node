// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.TokenService;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for building state for tests.
 *
 */
public class StateBuilderUtil {

    @NonNull
    protected MapReadableKVState.Builder<AccountID, Account> emptyReadableAccountStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, ACCOUNTS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<AccountID, Account> emptyWritableAccountStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, ACCOUNTS_STATE_ID);
    }

    @NonNull
    protected MapReadableKVState.Builder<PendingAirdropId, AccountPendingAirdrop> emptyReadableAirdropStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, AIRDROPS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<PendingAirdropId, AccountPendingAirdrop> emptyWritableAirdropStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, AIRDROPS_STATE_ID);
    }

    @NonNull
    protected MapReadableKVState.Builder<EntityIDPair, TokenRelation> emptyReadableTokenRelsStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, TOKEN_RELS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<EntityIDPair, TokenRelation> emptyWritableTokenRelsStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, TOKEN_RELS_STATE_ID);
    }

    @NonNull
    protected MapReadableKVState.Builder<NftID, Nft> emptyReadableNftStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, NFTS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<NftID, Nft> emptyWritableNftStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, NFTS_STATE_ID);
    }

    @NonNull
    protected MapReadableKVState.Builder<TokenID, Token> emptyReadableTokenStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, TOKENS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<TokenID, Token> emptyWritableTokenStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, TOKENS_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState.Builder<ProtoBytes, AccountID> emptyWritableAliasStateBuilder() {
        return MapWritableKVState.builder(TokenService.NAME, ALIASES_STATE_ID);
    }

    @NonNull
    protected MapReadableKVState.Builder<ProtoBytes, AccountID> emptyReadableAliasStateBuilder() {
        return MapReadableKVState.builder(TokenService.NAME, ALIASES_STATE_ID);
    }

    @NonNull
    protected MapWritableKVState<TokenID, Token> emptyWritableTokenState() {
        return MapWritableKVState.<TokenID, Token>builder(TokenService.NAME, TOKENS_STATE_ID)
                .build();
    }
}
