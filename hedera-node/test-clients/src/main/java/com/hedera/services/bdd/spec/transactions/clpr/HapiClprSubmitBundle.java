// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprSubmitBundle;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprSubmitBundleTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprSubmitBundle} transactions.
 */
public class HapiClprSubmitBundle extends HapiTxnOp<HapiClprSubmitBundle> {

    private byte[] channelId;
    private byte[] bundlePayload;
    private long endpointNodeId;

    public HapiClprSubmitBundle() {}

    public HapiClprSubmitBundle channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprSubmitBundle bundlePayload(final byte[] bundlePayload) {
        this.bundlePayload = bundlePayload;
        return this;
    }

    public HapiClprSubmitBundle endpointNodeId(final long endpointNodeId) {
        this.endpointNodeId = endpointNodeId;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprSubmitBundle;
    }

    @Override
    protected HapiClprSubmitBundle self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprSubmitBundleTransactionBody opBody = spec.txns()
                .<ClprSubmitBundleTransactionBody, ClprSubmitBundleTransactionBody.Builder>body(
                        ClprSubmitBundleTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (bundlePayload != null) {
                                b.setBundlePayload(ByteString.copyFrom(bundlePayload));
                            }
                            b.setEndpointNodeId(endpointNodeId);
                        });
        return b -> b.setClprSubmitBundle(opBody);
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
