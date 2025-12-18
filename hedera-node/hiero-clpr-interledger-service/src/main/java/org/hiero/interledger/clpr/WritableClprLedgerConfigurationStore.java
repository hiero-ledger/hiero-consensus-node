// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;

/**
 * The interface Writable CLPR Ledger Configuration store.
 */
public interface WritableClprLedgerConfigurationStore extends ReadableClprLedgerConfigurationStore {

    /**
     * Sets the ClprLedgerConfiguration in the state.
     *
     * @param ledgerConfiguration The ledger configuration to set.
     */
    void put(@NonNull ClprLedgerConfiguration ledgerConfiguration);
}
