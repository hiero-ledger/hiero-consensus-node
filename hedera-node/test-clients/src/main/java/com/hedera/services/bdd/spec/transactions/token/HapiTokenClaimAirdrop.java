// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;

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
import com.hederahashgraph.api.proto.java.PendingAirdropId;
import com.hederahashgraph.api.proto.java.TokenClaimAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenRejectTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody.Builder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class HapiTokenClaimAirdrop extends HapiTxnOp<HapiTokenClaimAirdrop> {

    private final List<Function<HapiSpec, PendingAirdropId>> pendingAirdropIds;

    @SafeVarargs
    @SuppressWarnings("varargs")
    public HapiTokenClaimAirdrop(final Function<HapiSpec, PendingAirdropId>... pendingAirdropIds) {
        this.pendingAirdropIds = List.of(pendingAirdropIds);
    }

    @Override
    protected HapiTokenClaimAirdrop self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenClaimAirdrop;
    }

    @Override
    protected Consumer<Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final var pendingAirdrops =
                pendingAirdropIds.stream().map(f -> f.apply(spec)).toList();
        final TokenClaimAirdropTransactionBody opBody = spec.txns()
                .<TokenClaimAirdropTransactionBody, TokenClaimAirdropTransactionBody.Builder>body(
                        TokenClaimAirdropTransactionBody.class, b -> b.addAllPendingAirdrops(pendingAirdrops));
        return builder -> builder.setTokenClaimAirdrop(opBody);
    }

    public static Function<HapiSpec, PendingAirdropId> pendingAirdrop(
            final String sender, final String receiver, final String token) {
        return spec -> {
            final var tokenID = TxnUtils.asTokenId(token, spec);
            final var senderID = TxnUtils.asId(sender, spec);
            final var receiverID = TxnUtils.asId(receiver, spec);
            return PendingAirdropId.newBuilder()
                    .setFungibleTokenType(tokenID)
                    .setSenderId(senderID)
                    .setReceiverId(receiverID)
                    .build();
        };
    }

    public static Function<HapiSpec, PendingAirdropId> pendingNFTAirdrop(
            final String sender, final String receiver, final String token, final long serialNum) {
        return spec -> {
            final var tokenID = TxnUtils.asTokenId(token, spec);
            final var senderID = TxnUtils.asId(sender, spec);
            final var receiverID = TxnUtils.asId(receiver, spec);
            return PendingAirdropId.newBuilder()
                    .setNonFungibleToken(NftID.newBuilder()
                            .setTokenID(tokenID)
                            .setSerialNumber(serialNum)
                            .build())
                    .setSenderId(senderID)
                    .setReceiverId(receiverID)
                    .build();
        };
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Stream.concat(
                        Stream.of(spec -> spec.registry().getKey(effectivePayer(spec))),
                        pendingAirdropIds.stream()
                                .map(pendingAirdropId -> (Function<HapiSpec, Key>) spec -> spec.registry()
                                        .getKey(spec.registry()
                                                .getAccountIdName(pendingAirdropId
                                                        .apply(spec)
                                                        .getSenderId()))))
                .toList();
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenClaimAirdrop,
                        (t, svo) -> usageEstimate(t, svo, spec.fees().tokenTransferUsageMultiplier()),
                        txn,
                        numPayerKeys);
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
