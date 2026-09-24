// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprCloseChannel;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprCloseChannelTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprCloseChannel} transactions.
 */
public class HapiClprCloseChannel extends HapiTxnOp<HapiClprCloseChannel> {

    private byte[] channelId;
    private byte[] ownershipCommitment;

    public HapiClprCloseChannel() {}

    public HapiClprCloseChannel channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprCloseChannel ownershipCommitment(final byte[] ownershipCommitment) {
        this.ownershipCommitment = ownershipCommitment;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprCloseChannel;
    }

    @Override
    protected HapiClprCloseChannel self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprCloseChannelTransactionBody opBody = spec.txns()
                .<ClprCloseChannelTransactionBody, ClprCloseChannelTransactionBody.Builder>body(
                        ClprCloseChannelTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (ownershipCommitment != null) {
                                b.setOwnershipCommitment(ByteString.copyFrom(ownershipCommitment));
                            }
                        });
        return b -> b.setClprCloseChannel(opBody);
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
