// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprRedactMessage;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprRedactMessageTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprRedactMessage} transactions.
 */
public class HapiClprRedactMessage extends HapiTxnOp<HapiClprRedactMessage> {

    private byte[] channelId;
    private long messageId;

    public HapiClprRedactMessage() {}

    public HapiClprRedactMessage channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprRedactMessage messageId(final long messageId) {
        this.messageId = messageId;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprRedactMessage;
    }

    @Override
    protected HapiClprRedactMessage self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprRedactMessageTransactionBody opBody = spec.txns()
                .<ClprRedactMessageTransactionBody, ClprRedactMessageTransactionBody.Builder>body(
                        ClprRedactMessageTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (messageId > 0) {
                                b.setMessageId(messageId);
                            }
                        });
        return b -> b.setClprRedactMessage(opBody);
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
