// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.token;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.extractTxnId;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiBaseTransfer;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TokenAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
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
}
