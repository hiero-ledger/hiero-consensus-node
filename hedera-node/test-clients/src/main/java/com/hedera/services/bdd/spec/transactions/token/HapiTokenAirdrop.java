// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import com.hedera.node.app.hapi.fees.usage.token.TokenAssociateUsage;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.fees.FeeCalculator;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractInfo;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountInfo;
import com.hedera.services.bdd.spec.transactions.HapiBaseTransfer;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TokenAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiTokenAirdrop extends HapiBaseTransfer<HapiTokenAirdrop> {
    static final Logger log = LogManager.getLogger(HapiTokenAirdrop.class);

    @Nullable
    private IntConsumer numAirdropsCreated = null;

    @Nullable
    private IntConsumer numTokenAssociationsCreated = null;

    @Nullable
    private BiConsumer<HapiSpec, TokenAirdropTransactionBody.Builder> explicitDef;

    public HapiTokenAirdrop(final TokenMovement... sources) {
        this.tokenAwareProviders = List.of(sources);
    }

    public HapiTokenAirdrop(@NonNull final BiConsumer<HapiSpec, TokenAirdropTransactionBody.Builder> explicitDef) {
        this.explicitDef = Objects.requireNonNull(explicitDef);
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        final var airdropFees = spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.TokenAirdrop,
                        (_txn, _svo) -> usageEstimate(_txn, _svo, spec.fees().tokenTransferUsageMultiplier()),
                        txn,
                        numPayerKeys);
        final var associationFees = estimateAssociationFees(spec, numPayerKeys);
        final var transferFees = estimateTransferFees(spec, numPayerKeys);
        return airdropFees + associationFees + transferFees;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenAirdrop;
    }

    @Override
    protected HapiTokenAirdrop self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final TokenAirdropTransactionBody opBody = spec.txns()
                .<TokenAirdropTransactionBody, TokenAirdropTransactionBody.Builder>body(
                        TokenAirdropTransactionBody.class, b -> {
                            if (explicitDef != null) {
                                explicitDef.accept(spec, b);
                            } else {
                                final var xfers = transfersAllFor(spec);
                                for (final TokenTransferList scopedXfers : xfers) {
                                    b.addTokenTransfers(scopedXfers);
                                }
                            }
                        });
        return builder -> builder.setTokenAirdrop(opBody);
    }

    public HapiTokenAirdrop airdropObserver(@Nullable final IntConsumer numAirdropsCreated) {
        this.numAirdropsCreated = numAirdropsCreated;
        return this;
    }

    public HapiTokenAirdrop tokenAssociationsObserver(@Nullable final IntConsumer numTokenAssociationsCreated) {
        this.numTokenAssociationsCreated = numTokenAssociationsCreated;
        return this;
    }

    @Override
    protected void assertExpectationsGiven(final HapiSpec spec) throws Throwable {
        if (numAirdropsCreated != null) {
            final var op = getTxnRecord(extractTxnId(txnSubmitted))
                    .assertingNothingAboutHashes()
                    .noLogging();
            CustomSpecAssert.allRunFor(spec, op);
            numAirdropsCreated.accept(op.getResponseRecord().getNewPendingAirdropsCount());
        }
        if (numTokenAssociationsCreated != null) {
            final var op = getTxnRecord(extractTxnId(txnSubmitted))
                    .assertingNothingAboutHashes()
                    .noLogging();
            CustomSpecAssert.allRunFor(spec, op);
            numTokenAssociationsCreated.accept(op.getResponseRecord().getAutomaticTokenAssociationsCount());
        }
    }

    private long estimateTransferFees(HapiSpec spec, int numPayerKeys) throws Throwable {
        // convert transfer list in to crypto transfer transaction
        var cryptoTransferBodyBuilder = CryptoTransferTransactionBody.newBuilder();
        final var xfers = transfersAllFor(spec);
        for (final TokenTransferList scopedXfers : xfers) {
            cryptoTransferBodyBuilder.addTokenTransfers(scopedXfers);
        }
        Consumer<TransactionBody.Builder> cryptoTransferTxnBodyConsumer =
                (b) -> b.setCryptoTransfer(cryptoTransferBodyBuilder.build());
        final Transaction.Builder builder2 = spec.txns().getReadyToSign(cryptoTransferTxnBodyConsumer, null, spec);

        final var transferTx = getSigned(spec, builder2, signersToUseFor(spec));

        // return estimated fee
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.CryptoTransfer,
                        (_txn, _svo) -> usageEstimate(_txn, _svo, spec.fees().tokenTransferUsageMultiplier()),
                        transferTx,
                        numPayerKeys);
    }

    private long estimateAssociationFees(HapiSpec spec, int numPayerKeys) throws Throwable {
        long totalFee = 0;
        for (var movement : tokenAwareProviders) {
            for (var receiver : movement.getAccountsWithMissingRelations(spec)) {
                // if account is not existing, we use default account id to estimate the new association
                var accountId = AccountID.getDefaultInstance();
                if (spec.registry().hasAccountId(receiver)) {
                    accountId = spec.registry().getKeyAlias(receiver);
                } else {
                    return ONE_HUNDRED_HBARS;
                }
                // create token associate transaction for each missing association
                var tokenAssociateTransactionBody = TokenAssociateTransactionBody.newBuilder()
                        .addTokens(spec.registry().getTokenID(movement.getToken()))
                        .setAccount(accountId)
                        .build();
                Consumer<TransactionBody.Builder> associateTxnConsumer =
                        (b) -> b.setTokenAssociate(tokenAssociateTransactionBody);
                final Transaction.Builder transactionBuilder =
                        spec.txns().getReadyToSign(associateTxnConsumer, null, spec);
                final var associationTx = getSigned(spec, transactionBuilder, signersToUseFor(spec));
                final var expiry = lookupExpiry(spec, receiver);
                FeeCalculator.ActivityMetrics metricsCalc = (_txn, svo) -> {
                    var estimate = TokenAssociateUsage.newEstimate(
                                    _txn, new TxnUsageEstimator(suFrom(svo), _txn, ESTIMATOR_UTILS))
                            .givenCurrentExpiry(expiry);
                    return estimate.get();
                };
                // estimate and add to total fee
                var tokenAssociateFees = spec.fees()
                        .forActivityBasedOp(
                                HederaFunctionality.TokenAssociateToAccount, metricsCalc, associationTx, numPayerKeys);
                totalFee += tokenAssociateFees;
            }
        }
        return totalFee;
    }

    private long lookupExpiry(HapiSpec spec, String account) {
        if (!spec.registry().hasContractId(account)) {
            HapiGetAccountInfo subOp;
            subOp = getAccountInfo(account).noLogging();
            Optional<Throwable> error = subOp.execFor(spec);
            if (error.isPresent()) {
                return 0;
            }
            return subOp.getResponse()
                    .getCryptoGetInfo()
                    .getAccountInfo()
                    .getExpirationTime()
                    .getSeconds();
        } else {
            HapiGetContractInfo subOp = getContractInfo(account).noLogging();
            Optional<Throwable> error = subOp.execFor(spec);
            if (error.isPresent()) {
                return 0;
            }
            return subOp.getResponse()
                    .getContractGetInfo()
                    .getContractInfo()
                    .getExpirationTime()
                    .getSeconds();
        }
    }
}
