// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Encapsulates the parts of a transaction we typically care about
 * for forensics purposes.
 */
public record TransactionParts(
        @NonNull Transaction wrapper, @NonNull TransactionBody body, @NonNull HederaFunctionality function) {
    public TransactionParts {
        requireNonNull(wrapper);
        requireNonNull(body);
        requireNonNull(function);
    }

    public static TransactionParts from(@NonNull final Transaction txn) {
        try {
            final var body = extractTransactionBody(txn);
            return new TransactionParts(txn, body, functionOf(body));
        } catch (InvalidProtocolBufferException | UnknownHederaFunctionality e) {
            // Fail immediately with invalid transactions that should not be
            // in any production record stream
            throw new IllegalArgumentException(e);
        }
    }
}
