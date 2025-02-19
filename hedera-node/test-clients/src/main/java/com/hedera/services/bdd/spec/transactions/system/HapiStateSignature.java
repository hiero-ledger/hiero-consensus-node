/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec.transactions.system;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
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
        final var transaction = Transaction.parseFrom(bytes.toByteArray());

        fiddler = Optional.of(it -> transaction);
        return b -> {};
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return 0;
    }
}
