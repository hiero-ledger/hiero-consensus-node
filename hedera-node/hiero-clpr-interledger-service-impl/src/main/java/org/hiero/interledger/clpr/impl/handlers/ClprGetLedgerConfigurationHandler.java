// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_LEDGER_CONFIGURATION_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.WAITING_FOR_LEDGER_ID;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationResponse;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

/**
 * Handles the {@link ClprGetLedgerConfigurationQuery} to retrieve the configuration of a CLPR ledger.
 * This handler uses the {@link ClprStateProofManager} to provide the ledger configuration and state proof.
 */
public class ClprGetLedgerConfigurationHandler extends FreeQueryHandler {
    private final ClprStateProofManager stateProofManager;

    /**
     * Constructor for injection of state proof manager.
     */
    @Inject
    public ClprGetLedgerConfigurationHandler(@NonNull final ClprStateProofManager stateProofManager) {
        requireNonNull(stateProofManager);
        this.stateProofManager = stateProofManager;
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.getClprLedgerConfigurationOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ClprGetLedgerConfigurationResponse.newBuilder().header(header);
        return Response.newBuilder().clprLedgerConfiguration(response).build();
    }

    @Override
    /**
     * Validates that a CLPR get-configuration query can be satisfied. The method ensures the caller
     * is querying a known ledger, distinguishes between “no configuration yet” and invalid proofs,
     * and pre-validates the returned proof so the later {@link #findResponse} call can reuse it.
     */
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        if (!stateProofManager.clprEnabled()) {
            throw new PreCheckException(ResponseCodeEnum.NOT_SUPPORTED);
        }
        final var query = context.query();
        final var op = query.getClprLedgerConfigurationOrThrow();
        // We are retrieving data from the StateProofManager since we are answering the query with a state proof.
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        // A null ledger id means we are waiting for the history service to determine this ledger's id.
        validateFalsePreCheck(localLedgerId == null, WAITING_FOR_LEDGER_ID);
        final var queryLedgerId = resolveLedgerIdToQuery(op, requireNonNull(localLedgerId));
        final var stateProof = stateProofManager.getLedgerConfiguration(queryLedgerId);
        if (stateProof == null) {
            throw new PreCheckException(CLPR_LEDGER_CONFIGURATION_NOT_AVAILABLE);
        }
        try {
            // TODO: Are either or both of these necessary?
            ClprStateProofUtils.validateStateProof(stateProof);
            ClprStateProofUtils.extractConfiguration(stateProof);
        } catch (final IllegalArgumentException | IllegalStateException e) {
            throw new PreCheckException(CLPR_INVALID_STATE_PROOF);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var op = query.getClprLedgerConfigurationOrThrow();
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        final var queryLedgerId = resolveLedgerIdToQuery(op, requireNonNull(localLedgerId));
        // we assume that the ledger id is valid and retrieves a valid configuration
        // TODO: how do we know this is the same one that we validated earlier?
        final var proof = stateProofManager.getLedgerConfiguration(queryLedgerId);
        final var response = ClprGetLedgerConfigurationResponse.newBuilder()
                .header(header)
                .ledgerConfigurationProof(proof)
                .build();
        return Response.newBuilder().clprLedgerConfiguration(response).build();
    }

    /**
     * Resolves the {@link ClprLedgerId} from the given {@link ClprGetLedgerConfigurationQuery}.
     * If the ledger id is not provided, it returns the local ledger id.
     *
     * @param query         The query containing the ledger id.
     * @param localLedgerId The ledger id of this ledger.
     * @return The resolved {@link ClprLedgerId}.
     */
    private static ClprLedgerId resolveLedgerIdToQuery(
            @NonNull final ClprGetLedgerConfigurationQuery query, @NonNull final ClprLedgerId localLedgerId) {
        final var ledgerId = query.ledgerId();
        if (ledgerId == null || ledgerId.ledgerId() == Bytes.EMPTY) {
            // A missing ledger id is valid as a query for this ledger's current configuration.
            return localLedgerId;
        }
        return ledgerId;
    }
}
