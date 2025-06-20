// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.fixtures;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.service.token.api.FeeStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A fake implementation of {@link FeeStreamBuilder} for testing purposes.
 */
public class FakeFeeRecordBuilder implements FeeStreamBuilder {
    private long transactionFee;

    public FakeFeeRecordBuilder() {
        // Just something to keep checkModuleInfo from claiming we don't require com.hedera.node.hapi
        requireNonNull(SemanticVersion.class);
    }

    @Override
    public long transactionFee() {
        return transactionFee;
    }

    @Override
    @NonNull
    public FeeStreamBuilder transactionFee(long transactionFee) {
        this.transactionFee = transactionFee;
        return this;
    }
}
