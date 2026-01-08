// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.client;

import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

/**
 * Interface for the CLPR (Cross-Ledger Protocol) client.
 */
public interface ClprClient extends AutoCloseable {

    /**
     * Retrieves the CLPR ledger configuration proof for the remote CLPR Endpoint's local ledger.
     *
     * @return state proof containing the remote's local configuration, or {@code null} if unavailable
     */
    @Nullable
    StateProof getConfiguration();

    /**
     * Retrieves the CLPR ledger configuration proof for the given ledger id, if it exists in the remote ledger's state.
     *
     * @param ledgerId target ledger id (blank means remote's local ledger)
     * @return state proof containing the requested configuration, or {@code null} if unavailable
     */
    @Nullable
    StateProof getConfiguration(@NonNull ClprLedgerId ledgerId);

    /**
     * Submits a CLPR ledger configuration proof to the remote CLPR endpoint.
     *
     * @param ledgerConfigurationProof the state proof wrapping the configuration to set
     * @return precheck status for the submission
     */
    @NonNull
    ResponseCodeEnum setConfiguration(
            @NonNull AccountID payerAccountId,
            @NonNull AccountID nodeAccountId,
            @NonNull StateProof ledgerConfigurationProof);

    /**
     * Closes the CLPR client connection.
     */
    @Override
    void close();
}
