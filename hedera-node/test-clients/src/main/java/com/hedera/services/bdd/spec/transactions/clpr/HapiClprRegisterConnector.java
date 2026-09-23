// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.clpr;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.ClprRegisterConnector;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.ClprRegisterConnectorTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HAPI spec operation for {@code ClprRegisterConnector} (Phase 1: Commit) transactions.
 */
public class HapiClprRegisterConnector extends HapiTxnOp<HapiClprRegisterConnector> {

    private byte[] commitment;

    public HapiClprRegisterConnector() {}

    public HapiClprRegisterConnector commitment(final byte[] commitment) {
        this.commitment = commitment;
        return this;
    }

    // Keep for test-suite backward compatibility — maps old calls to no-ops
    public HapiClprRegisterConnector sourceConnectorAddress(final byte[] ignored) {
        return this;
    }

    public HapiClprRegisterConnector connectorContract(final String ignored) {
        return this;
    }

    public HapiClprRegisterConnector lockedStake(final long ignored) {
        return this;
    }

    public HapiClprRegisterConnector adminKey(final String ignored) {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return ClprRegisterConnector;
    }

    @Override
    protected HapiClprRegisterConnector self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final ClprRegisterConnectorTransactionBody opBody = spec.txns()
                .<ClprRegisterConnectorTransactionBody, ClprRegisterConnectorTransactionBody.Builder>body(
                        ClprRegisterConnectorTransactionBody.class, b -> {
                            if (commitment != null) {
                                b.setCommitment(ByteString.copyFrom(commitment));
                            }
                        });
        return b -> b.setClprRegisterConnector(opBody);
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
