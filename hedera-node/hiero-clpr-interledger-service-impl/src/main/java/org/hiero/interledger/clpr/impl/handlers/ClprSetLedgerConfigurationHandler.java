package org.hiero.interledger.clpr.impl.handlers;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.*;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

import javax.inject.Inject;

import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

public class ClprSetLedgerConfigurationHandler implements TransactionHandler {

    private final ClprStateProofManager stateProofManager;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ClprSetLedgerConfigurationHandler(@NonNull final ClprStateProofManager stateProofManager) {
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        pureChecks(context.body());
    }

    private void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        validateTruePreCheck(txn.hasClprLedgerConfiguration(), ResponseCodeEnum.INVALID_TRANSACTION_BODY);
        final var configTxn = txn.clprLedgerConfigurationOrThrow();
        validateTruePreCheck(configTxn.hasLedgerConfiguration(), ResponseCodeEnum.INVALID_TRANSACTION);
        final var ledgerConfig = configTxn.ledgerConfigurationOrThrow();
        final var ledgerId = ledgerConfig.ledgerIdOrThrow();
        //ledgerId must exist.
        validateTruePreCheck(ledgerId.ledgerId() != Bytes.EMPTY, ResponseCodeEnum.INVALID_TRANSACTION);
        //endpoints must not be empty.
        validateFalsePreCheck(ledgerConfig.endpoints().isEmpty(), ResponseCodeEnum.INVALID_TRANSACTION);
        //TODO: Check that certificates are non-empty and valid for each endpoint.
        //CLPR Interledger capability is not supported until the local ledger id as been determined.
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        validateFalsePreCheck(localLedgerId.ledgerId() == Bytes.EMPTY, ResponseCodeEnum.WAITING_FOR_LEDGER_ID);
        //This code path is not a way of setting the local ledger configuration.
        validateFalsePreCheck(localLedgerId.equals(ledgerConfig.ledgerId()), ResponseCodeEnum.INVALID_TRANSACTION);
        //The ledger configuration being set must be more recent than the existing configuration.
        final var existingConfig = stateProofManager.getLedgerConfiguration(ledgerId);
        if (existingConfig != null) {
            final var existingConfigTime = existingConfig.timestampOrThrow();
            final var newConfigTime = ledgerConfig.timestampOrThrow();
            validateFalsePreCheck(existingConfigTime.seconds() > newConfigTime.seconds()
                            || (existingConfigTime.seconds() == newConfigTime.seconds()
                            && existingConfigTime.nanos() >= newConfigTime.nanos()),
                    ResponseCodeEnum.INVALID_TRANSACTION);
        }
        //The state proof must be valid and signed before submitting it to the network.
        validateTruePreCheck(stateProofManager.validateStateProof(configTxn), ResponseCodeEnum.CLPR_INVALID_STATE_PROOF);
    }


    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // All nodes need to check that the ledger configuration is an update and that the state proof is valid.
        // If any of the pure checks fail, the transaction will not be processed and the submitting nodes will need
        // to be held accountable for the failure.
        final var txn = context.body();
        final var submittingNode = context.creatorInfo();
        try {
            //TODO: This call needs to have a deterministic result.
            // How do we ensure that the configuration in the latest block proven state hasn't changed across all nodes?
            pureChecks(txn);
        } catch (PreCheckException e) {
            //TODO: The submitting nodes should be held accountable for the failure.

            // If the pure checks fail, we throw a PreCheckException to indicate that the transaction is invalid.
            throw e;
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // We assume that the state proof is valid and the configuration is ready to be set.
        // We'll check one last time that the ledger configuration is an update of what exists in the state.

        throw new UnsupportedOperationException("Not supported yet.");
    }
}
