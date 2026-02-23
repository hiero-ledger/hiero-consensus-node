// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.system;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Optional;
import java.util.function.Consumer;

public class HapiStateSignature extends HapiTxnOp<HapiStateSignature> {
    @Override
    protected HapiStateSignature self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.StateSignatureTransaction;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        // we use the opportunity to test the factory method Hedera.encodeSystemTransaction()
        final var stateSignatureTransaction = StateSignatureTransaction.newBuilder()
                .round(42L)
                .signature(Bytes.wrap("signature_123"))
                .hash(Bytes.wrap("hash_123"))
                .build();
        final var bytes = spec.embeddedHederaOrThrow().hedera().encodeSystemTransaction(stateSignatureTransaction);
        final var signedTx = SignedTransaction.parseFrom(bytes.toByteArray());

        fiddler = Optional.of(ignore -> Transaction.newBuilder()
                .setSignedTransactionBytes(signedTx.toByteString())
                .build());
        return b -> {};
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return 0;
    }
}
