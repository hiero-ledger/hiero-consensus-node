// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.TokenReference;
import com.hederahashgraph.api.proto.java.TokenRejectTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiTokenReject extends HapiTxnOp<HapiTokenReject> {

    private String account;
    private final List<Function<HapiSpec, TokenReference>> referencesSources;

    @SafeVarargs
    @SuppressWarnings("varargs")
    public HapiTokenReject(final String account, final Function<HapiSpec, TokenReference>... tokenReferencesSources) {
        this.account = account;
        this.referencesSources = List.of(tokenReferencesSources);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public HapiTokenReject(final Function<HapiSpec, TokenReference>... tokenReferencesSources) {
        this.referencesSources = List.of(tokenReferencesSources);
    }

    public static Function<HapiSpec, TokenReference> rejectingToken(final String token) {
        return spec -> {
            final var tokenID = TxnUtils.asTokenId(token, spec);
            return TokenReference.newBuilder().setFungibleToken(tokenID).build();
        };
    }

    public static Function<HapiSpec, TokenReference> rejectingNFT(final String token, final long serialNum) {
        return spec -> {
            final var tokenID = TxnUtils.asTokenId(token, spec);
            return TokenReference.newBuilder()
                    .setNft(NftID.newBuilder()
                            .setTokenID(tokenID)
                            .setSerialNumber(serialNum)
                            .build())
                    .build();
        };
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenReject;
    }

    @Override
    protected HapiTokenReject self() {
        return this;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper().add("account", account).add("rejectedTokens", referencesSources);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        final List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer(spec)));
        if (account != null) {
            signers.add(spec -> spec.registry().getKey(account));
        }
        return signers;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final List<TokenReference> tokenReferences = referencesSources.stream()
                .map(refSource -> refSource.apply(spec))
                .toList();
        final TokenRejectTransactionBody.Builder opBuilder =
                TokenRejectTransactionBody.newBuilder().addAllRejections(tokenReferences);
        if (account != null) {
            opBuilder.setOwner(TxnUtils.asId(account, spec));
        }
        return b -> b.setTokenReject(opBuilder);
    }
}
