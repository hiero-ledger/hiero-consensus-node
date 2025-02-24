// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions;

import com.hederahashgraph.api.proto.java.TransactionID;

public class SequentialID {
    int currentTxnNonce = 0;
    TransactionID parent;

    public SequentialID(TransactionID distributeTxId) {
        parent = distributeTxId;
    }

    public TransactionID nextChild() {
        return parent.toBuilder().setNonce(++currentTxnNonce).build();
    }
}
