// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Bundles the handlers for the {@link com.hedera.node.app.history.HistoryService}.
 * @param historyProofSignatureHandler the handler for assembly signatures
 * @param historyProofKeyPublicationHandler the handler for key publication
 * @param historyProofVoteHandler the handler for proof votes
 */
public record HistoryHandlers(
        @NonNull HistoryProofSignatureHandler historyProofSignatureHandler,
        @NonNull HistoryProofKeyPublicationHandler historyProofKeyPublicationHandler,
        @NonNull HistoryProofVoteHandler historyProofVoteHandler) {
    public HistoryHandlers {
        requireNonNull(historyProofSignatureHandler);
        requireNonNull(historyProofKeyPublicationHandler);
        requireNonNull(historyProofVoteHandler);
    }
}
