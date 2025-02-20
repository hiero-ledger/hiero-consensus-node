// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOpFinisher;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;

public class DelegatingOpFinisher implements HapiSpecOpFinisher {
    private final HapiTxnOp<?> delegate;

    public DelegatingOpFinisher(HapiTxnOp<?> delegate) {
        this.delegate = delegate;
    }

    @Override
    public long submitTime() {
        return delegate.getSubmitTime();
    }

    @Override
    public void finishFor(HapiSpec spec) throws Throwable {
        delegate.finalizeExecFor(spec);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
