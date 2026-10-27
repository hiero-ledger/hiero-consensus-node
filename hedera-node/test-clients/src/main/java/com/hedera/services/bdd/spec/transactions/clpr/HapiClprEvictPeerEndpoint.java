// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprEvictPeerEndpoint;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprEvictPeerEndpointTransactionBody;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprEvictPeerEndpoint} transactions.
 */
public class HapiClprEvictPeerEndpoint extends HapiTxnOp<HapiClprEvictPeerEndpoint> {

    private byte[] channelId;
    private String ipAddress;
    private int port;

    public HapiClprEvictPeerEndpoint() {}

    public HapiClprEvictPeerEndpoint channelId(final byte[] channelId) {
        this.channelId = channelId;
        return this;
    }

    public HapiClprEvictPeerEndpoint endpoint(final String ipAddress, final int port) {
        this.ipAddress = ipAddress;
        this.port = port;
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprEvictPeerEndpoint;
    }

    @Override
    protected HapiClprEvictPeerEndpoint self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprEvictPeerEndpointTransactionBody opBody = spec.txns()
                .<ClprEvictPeerEndpointTransactionBody, ClprEvictPeerEndpointTransactionBody.Builder>body(
                        ClprEvictPeerEndpointTransactionBody.class, b -> {
                            if (channelId != null) {
                                b.setChannelId(ByteString.copyFrom(channelId));
                            }
                            if (ipAddress != null) {
                                b.setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                        .setIpAddress(ipAddress)
                                        .setPort(port)
                                        .build());
                            }
                        });
        return b -> b.setClprEvictPeerEndpoint(opBody);
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
