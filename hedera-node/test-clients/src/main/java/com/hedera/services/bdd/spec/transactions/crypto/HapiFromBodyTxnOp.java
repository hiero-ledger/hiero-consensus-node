// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.crypto;

import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.function.Consumer;

/**
 * Used for creating transactions from pre-defined transaction bodies.
 * <p>
 * Designed for simple validation scenarios, particularly when testing unsupported operations.
 * Instead of creating new HapiTxnOp subclasses for each unsupported transaction type,
 * this universal operation can be used by supplying the desired transaction body
 * and functionality type.
 */
public class HapiFromBodyTxnOp extends HapiTxnOp<HapiFromBodyTxnOp> {

    private final TransactionBody body;
    private final HederaFunctionality functionality;

    /**
     * Create transaction op from transaction body.
     *
     * @param functionality transaction type.
     * @param body transaction body.
     */
    public HapiFromBodyTxnOp(final HederaFunctionality functionality, final TransactionBody body) {
        this.body = body;
        this.functionality = functionality;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, final int numPayerKeys) throws Throwable {
        return ONE_HBAR;
    }

    @Override
    public HederaFunctionality type() {
        return functionality;
    }

    @Override
    protected HapiFromBodyTxnOp self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(final HapiSpec spec) throws Throwable {
        return b -> b.mergeFrom(this.body);
    }
}
