// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoTransferMeta;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.TokenReference;
import com.hederahashgraph.api.proto.java.TokenRejectTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
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
    protected long feeFor(final HapiSpec spec, final Transaction transaction, final int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenReject,
                        (txn, svo) -> usageEstimate(txn, svo, spec.fees().tokenTransferUsageMultiplier()),
                        transaction,
                        numPayerKeys);
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

    private static FeeData usageEstimate(
            @NonNull final TransactionBody txn, @NonNull final SigValueObj svo, final int multiplier) {
        final var op = txn.getTokenReject();

        // We only have direct token transfers to the treasury being sent when using the TokenReject operation.
        // Since there are 0 hBar transfers we pass 0 for the numExplicitTransfers in the BaseTransactionMeta.
        final var baseMeta = new BaseTransactionMeta(txn.getMemoBytes().size(), 0);
        final var xferUsageMeta = getCryptoTransferMeta(multiplier, op);

        final var accumulator = new UsageAccumulator();
        cryptoOpsUsage.cryptoTransferUsage(suFrom(svo), xferUsageMeta, baseMeta, accumulator, 0, false);

        final var feeData = AdapterUtils.feeDataFrom(accumulator);
        return feeData.toBuilder().setSubType(xferUsageMeta.getSubType()).build();
    }

    @NonNull
    private static CryptoTransferMeta getCryptoTransferMeta(
            final int multiplier, @NonNull final TokenRejectTransactionBody op) {
        final int numTokensInvolved = op.getRejectionsCount();
        int numTokenRejections = 0;
        int numNftRejections = 0;
        for (final var tokenRejection : op.getRejectionsList()) {
            if (tokenRejection.hasFungibleToken()) {
                // Each fungible token rejection involves 2 AccountAmount transfers
                // We add 2 in order to match CryptoTransfer's bpt & rbs fee calculation
                numTokenRejections += 2;
            } else {
                numNftRejections++;
            }
        }
        return new CryptoTransferMeta(multiplier, numTokensInvolved, numTokenRejections, numNftRejections, false);
    }
}
