// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

/**
 * Manages the latest immutable state that has a valid block proof.  This includes reading the state for needed content,
 * returning state proofs for content in the state, and validating state proofs.
 */
@Singleton
public class ClprStateProofManager {

    @Inject
    public ClprStateProofManager() {}

    /**
     * Gets the local ledger ID from the latest provable state.
     *
     * @return The local ledger ID, or null if not determined yet.
     */
    @Nullable
    public ClprLedgerId getLocalLedgerId() {
        throw new UnsupportedOperationException("getLocalLedgerId() is not implemented yet.");
    }

    /**
     * Gets the ledger configuration for the given ledger ID from the latest provable state.
     *
     * @param ledgerId The ID of the ledger for which to retrieve the configuration.
     * @return The {@link ClprLedgerConfiguration} for the specified ledger ID, or null if not found.
     */
    @Nullable
    public ClprLedgerConfiguration getLedgerConfiguration(@NonNull ClprLedgerId ledgerId) {
        // TODO: Implement the logic to retrieve the ledger configuration for the given ledger ID.
        return null;
    }

    /**
     * Validates the signature and state proof within the given {@link ClprSetLedgerConfigurationTransactionBody}.
     * In this case the configuration for validating the state proof and signature are in the content being proven.
     *
     * @param configTxn The transaction body containing the state proof of the ledger configuration to validate.
     * @return {@code true} if the state proof is valid, otherwise {@code false}.
     */
    public boolean validateStateProof(@NonNull final ClprSetLedgerConfigurationTransactionBody configTxn) {
        // TODO: Implement the logic to validate the state proof and the signature on the state proof.
        return true;
    }
}
