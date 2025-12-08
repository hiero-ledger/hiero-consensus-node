// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Bundles the handlers for the {@link org.hiero.interledger.clpr.ClprService}.
 */
@Singleton
public class ClprLedgerConfigurationHandlers {

    private final ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler;
    private final ClprSetLedgerConfigurationHandler clprSetLedgerConfigurationHandler;

    @Inject
    public ClprLedgerConfigurationHandlers(
            @NonNull final ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler,
            @NonNull final ClprSetLedgerConfigurationHandler clprSetLedgerConfigurationHandler) {
        this.clprGetLedgerConfigurationHandler = requireNonNull(clprGetLedgerConfigurationHandler);
        this.clprSetLedgerConfigurationHandler = requireNonNull(clprSetLedgerConfigurationHandler);
    }

    /**
     * Returns the handler for getting ledger configurations.
     *
     * @return the handler for getting ledger configurations
     */
    public ClprGetLedgerConfigurationHandler clprGetLedgerConfigurationHandler() {
        return clprGetLedgerConfigurationHandler;
    }

    /**
     * Returns the handler for setting ledger configurations.
     *
     * @return the handler for setting ledger configurations
     */
    public ClprSetLedgerConfigurationHandler clprSetLedgerConfigurationHandler() {
        return clprSetLedgerConfigurationHandler;
    }
}
