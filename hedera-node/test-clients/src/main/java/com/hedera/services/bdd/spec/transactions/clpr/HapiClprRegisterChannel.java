// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprRegisterChannel;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprRegisterChannelTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprRegisterChannel} transactions.
 */
public class HapiClprRegisterChannel extends HapiTxnOp<HapiClprRegisterChannel> {

    private byte[] ownershipCommitment;

    /** Covers the $100 CLPR price at the test exchange rate; an explicit {@link #fee} overrides it. */
    private static final long DEFAULT_MAX_FEE_TINYBARS = 1_000L * 100_000_000L;

    public HapiClprRegisterChannel() {
        fee(DEFAULT_MAX_FEE_TINYBARS);
    }

    public HapiClprRegisterChannel ownershipCommitment(final byte[] commitment) {
        this.ownershipCommitment = commitment;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprRegisterChannel;
    }

    @Override
    protected HapiClprRegisterChannel self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprRegisterChannelTransactionBody opBody = spec.txns()
                .<ClprRegisterChannelTransactionBody, ClprRegisterChannelTransactionBody.Builder>body(
                        ClprRegisterChannelTransactionBody.class, b -> {
                            if (ownershipCommitment != null) {
                                b.setOwnershipCommitment(ByteString.copyFrom(ownershipCommitment));
                            }
                        });
        return b -> b.setClprRegisterChannel(opBody);
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return List.of(spec -> spec.registry().getKey(effectivePayer(spec)));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        return super.toStringHelper();
    }
}
