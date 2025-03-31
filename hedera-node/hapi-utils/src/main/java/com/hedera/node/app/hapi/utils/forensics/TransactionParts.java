// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.forensics;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.node.app.hapi.utils.CommonUtils.functionOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.pbj.runtime.ParseException;
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
        } catch (UnknownHederaFunctionality | ParseException e) {
            // Fail immediately with invalid transactions that should not be
            // in any production record stream
            throw new IllegalArgumentException(e);
        }
    }
}
