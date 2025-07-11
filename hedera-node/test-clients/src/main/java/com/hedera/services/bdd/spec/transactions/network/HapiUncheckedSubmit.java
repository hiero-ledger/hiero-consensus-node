// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.network;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.UncheckedSubmit;

import com.google.common.base.MoreObjects;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiUncheckedSubmit<T extends HapiTxnOp<T>> extends HapiTxnOp<HapiUncheckedSubmit<T>> {
    private static final Logger log = LogManager.getLogger(HapiUncheckedSubmit.class);

    private final HapiTxnOp<T> subOp;

    public HapiUncheckedSubmit(final HapiTxnOp<T> subOp) {
        this.subOp = subOp;
        this.hasAnyStatusAtAll();
    }

    @Override
    protected HapiUncheckedSubmit<T> self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return UncheckedSubmit;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        final var tx = subOp.signedTxnFor(spec);
        if (verboseLoggingOn) {
            log.info("Submitting unchecked: {}", CommonUtils.extractTransactionBody(tx));
        }
        final var subOpBytes = HapiTxnOp.serializedSignedTxFrom(subOp.signedTxnFor(spec));
        final UncheckedSubmitBody opBody = spec.txns()
                .<UncheckedSubmitBody, UncheckedSubmitBody.Builder>body(
                        UncheckedSubmitBody.class, b -> b.setTransactionBytes(ByteString.copyFrom(subOpBytes)));
        return b -> b.setUncheckedSubmit(opBody);
    }

    @Override
    protected long feeFor(final HapiSpec spec, final Transaction txn, final int numPayerKeys) {
        return 0L;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        final MoreObjects.ToStringHelper helper = super.toStringHelper();
        return helper;
    }
}
