// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.transaction.Transaction;

public class BadMetadataException extends Exception {
    public BadMetadataException(@NonNull final Transaction txn) {
        super("Failed to find PreHandleResult in transaction metadata (%s)".formatted(txn.getMetadata()));
    }
}
