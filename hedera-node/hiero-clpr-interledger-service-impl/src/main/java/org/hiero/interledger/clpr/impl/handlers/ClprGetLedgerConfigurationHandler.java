package org.hiero.interledger.clpr.impl.handlers;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.history.ReadableHistoryStore;
import com.hedera.node.app.spi.workflows.FreeQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationQuery;
import org.hiero.hapi.interledger.clpr.ClprGetLedgerConfigurationResponse;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ReadableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

import javax.inject.Inject;

import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

public class ClprGetLedgerConfigurationHandler extends FreeQueryHandler {
    private final ClprStateProofManager stateProofManager;

    /**
     * Default constructor for injection.
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
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var ledgerConfigStore = context.createStore(ReadableClprLedgerConfigurationStore.class);
        final var op = query.getClprLedgerConfigurationOrThrow();
        final var ledgerId = resolveLedgerId(op, context);
        // A null ledger id means we are waiting for the history service to determine this ledger's id.
        validateFalsePreCheck(ledgerId == null, WAITING_FOR_LEDGER_ID);
        final var ledgerConfiguration = ledgerConfigStore.get(ledgerId);
        validateFalsePreCheck(ledgerConfiguration == null, CLPR_LEDGER_CONFIGURATION_NOT_AVAILABLE);
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        //TODO: May need to create stores through the state proof manager instead of the context.
        final var ledgerConfigStore = context.createStore(ReadableClprLedgerConfigurationStore.class);
        final var op = query.getClprLedgerConfigurationOrThrow();
        final var ledgerId = resolveLedgerId(op, context);
        //we assume that the ledger id is valid and retrieves a valid configuration
        final var response = ClprGetLedgerConfigurationResponse.newBuilder()
                .header(header)
                .ledgerConfiguration(ledgerConfigStore.get(requireNonNull(ledgerId)))
                .build();
        //TODO: add state proof to the response.
        return Response.newBuilder().clprLedgerConfiguration(response).build();
    }

    /**
     * Resolves the {@link ClprLedgerId} from the given {@link ClprGetLedgerConfigurationQuery}.
     * If the ledger id is not provided, it retrieves the current ledger id from the history store.
     *
     * @param query   The query containing the ledger id.
     * @param context The query context.
     * @return The resolved {@link ClprLedgerId}.
     */
    private static ClprLedgerId resolveLedgerId(@NonNull final ClprGetLedgerConfigurationQuery query, @NonNull final QueryContext context) {
        var ledgerId = query.ledgerId();
        if (ledgerId == null || ledgerId.ledgerId() == Bytes.EMPTY) {
            // A missing ledger id is valid as a query for this ledger's current configuration.
            // Get this ledger's id from the history store.
            final var historyStore = context.createStore(ReadableHistoryStore.class);
            final var rawLedgerId = historyStore.getLedgerId();
            // If the raw ledger id is null, we return null,
            // indicating that we are waiting for the history service to determine the ledger id.
            ledgerId = rawLedgerId == null || rawLedgerId == Bytes.EMPTY ? null
                    : ClprLedgerId.newBuilder().ledgerId(requireNonNull(rawLedgerId)).build();
        }
        return ledgerId;
    }
}
