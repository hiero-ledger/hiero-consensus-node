// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.client;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

/**
 * Interface for the CLPR (Cross-Ledger Protocol) client.
 */
public interface ClprClient extends AutoCloseable {

    /**
     * Retrieves the CLPR ledger configuration for the remote CLPR Endpoint's ledger.
     *
     * @return the CLPR ledger configuration.
     */
    ClprLedgerConfiguration getConfiguration();

    /**
     * Retrieves the CLPR ledger configuration for the given ledger id, if it exists in the remote ledger's state.
     *
     * @return the CLPR ledger configuration.
     */
    @Nullable
    ClprLedgerConfiguration getConfiguration(@NonNull ClprLedgerId ledgerId);

    /**
     * Submits the CLPR ledger configuration to the remote CLPR Endpoint.
     *
     * @param clprLedgerConfiguration the CLPR ledger configuration to set.
     */
    @NonNull
    ResponseCodeEnum setConfiguration(
            @NonNull AccountID payerAccountId,
            @NonNull AccountID nodeAccountId,
            @NonNull ClprLedgerConfiguration clprLedgerConfiguration);

    /**
     * Closes the CLPR client connection.
     */
    @Override
    void close();
}
