// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The interface Writable CLPR Ledger Configuration store.
 */
public interface WritableClprLedgerConfigurationStore extends ReadableClprLedgerConfigurationStore {

    /**
     * Sets the ClprLedgerConfiguration in the state.
     *
     * @param ledgerConfiguration The ledger configuration to set.
     */
    void setLedgerConfiguration(@NonNull ClprLedgerConfiguration ledgerConfiguration);
}
