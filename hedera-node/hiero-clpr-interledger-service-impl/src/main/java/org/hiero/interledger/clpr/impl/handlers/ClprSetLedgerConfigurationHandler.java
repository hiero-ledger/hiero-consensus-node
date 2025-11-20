// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.*;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.interledger.clpr.WritableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

/**
 * Handles the {@link  org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody} to set the
 * configuration of a CLPR ledger.
 * This handler uses the {@link ClprStateProofManager} to validate the state proof and manage ledger configurations.
 */
public class ClprSetLedgerConfigurationHandler implements TransactionHandler {

    private final ClprStateProofManager stateProofManager;
    private final NetworkInfo networkInfo;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ClprSetLedgerConfigurationHandler(
            @NonNull final ClprStateProofManager stateProofManager, @NonNull final NetworkInfo networkInfo) {
        this.stateProofManager = requireNonNull(stateProofManager);
        this.networkInfo = requireNonNull(networkInfo);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        // TODO: Determine what throttles apply to this transaction.
        //  Number of state proofs per second?
        //  Number of ledger configurations per second?
        //  Number of ledger configurations per ledger id per second?
        pureChecks(context.body());
    }

    /**
     * Performs the pre-checks for the CLPR ledger configuration transaction.
     * These checks are performed on the submitting node during {@link this.pureCheck()} and again by all nodes during
     * {@link this.preHandle()}.  The transaction and ledger configuraiton is validated for currectness and the
     * state proof is verified.
     *
     * @param txn The transaction body containing the CLPR ledger configuration to validate.
     * @throws PreCheckException If any of the checks fail, indicating an invalid transaction.
     */
    private void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        pureChecks(txn, false);
    }

    private void pureChecks(@NonNull final TransactionBody txn, final boolean isLocal) throws PreCheckException {
        final var clprSetLedgerConfiguration = txn.clprSetLedgerConfiguration();
        validateTruePreCheck(clprSetLedgerConfiguration != null, ResponseCodeEnum.INVALID_TRANSACTION_BODY);

        final var configTxn = clprSetLedgerConfiguration;
        validateTruePreCheck(configTxn.hasLedgerConfiguration(), ResponseCodeEnum.INVALID_TRANSACTION);
        final var ledgerConfig = configTxn.ledgerConfigurationOrThrow();
        final var ledgerId = ledgerConfig.ledgerIdOrThrow();
        // ledgerId must exist.
        validateTruePreCheck(ledgerId.ledgerId() != Bytes.EMPTY, ResponseCodeEnum.INVALID_TRANSACTION);
        // endpoints must not be empty.
        validateFalsePreCheck(ledgerConfig.endpoints().isEmpty(), ResponseCodeEnum.INVALID_TRANSACTION);
        // TODO: Check that certificates are non-empty and valid for each endpoint.
        // CLPR Interledger capability is not supported until the local ledger id as been determined.
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        validateFalsePreCheck(localLedgerId.ledgerId() == Bytes.EMPTY, ResponseCodeEnum.WAITING_FOR_LEDGER_ID);
        // This code path is not a way of setting the local ledger configuration.
        validateFalsePreCheck(localLedgerId.equals(ledgerConfig.ledgerId()), ResponseCodeEnum.INVALID_TRANSACTION);
        // The ledger configuration being set must be more recent than the existing configuration.
        final var existingConfig = stateProofManager.getLedgerConfiguration(ledgerId);
        if (existingConfig != null) {
            final var existingConfigTime = existingConfig.timestampOrThrow();
            final var newConfigTime = ledgerConfig.timestampOrThrow();
            validateFalsePreCheck(
                    existingConfigTime.seconds() > newConfigTime.seconds()
                            || (existingConfigTime.seconds() == newConfigTime.seconds()
                                    && existingConfigTime.nanos() >= newConfigTime.nanos()),
                    ResponseCodeEnum.INVALID_TRANSACTION);
        }
        if (!isLocal) {
            // The state proof must be valid and signed before submitting it to the network.
            validateTruePreCheck(
                    stateProofManager.validateStateProof(configTxn), ResponseCodeEnum.CLPR_INVALID_STATE_PROOF);
        }
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
            // TODO: This call needs to have a deterministic result.
            // How do we ensure that the configuration in the latest block proven state hasn't changed across all nodes?
            // We could keep a 10 minute recent history of block signed states and use the greatest one prior to
            // the transaction time.
            pureChecks(
                    txn, submittingNode.nodeId() == networkInfo.selfNodeInfo().nodeId());
        } catch (PreCheckException e) {
            // TODO: The submitting nodes should be held accountable for the failure.

            // If the pure checks fail, we throw a PreCheckException to indicate that the transaction is invalid.
            throw e;
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // We assume that the state proof is valid and the configuration is ready to be set.
        final var txn = context.body();
        final var configTxn = txn.clprSetLedgerConfigurationOrThrow();
        final var newConfig = configTxn.ledgerConfigurationOrThrow();
        final var ledgerId = newConfig.ledgerIdOrThrow();
        final var configStore = context.storeFactory().writableStore(WritableClprLedgerConfigurationStore.class);
        final var existingConfig = configStore.get(ledgerId);
        if (updatesConfig(existingConfig, newConfig)) {
            // If the configuration is an update, we store it in the writable store.
            configStore.put(newConfig);
        }
    }

    /**
     * Determines if the new configuration updates the existing configuration.
     *
     * @param existingConfig The existing configuration, or null if there is no existing configuration.
     * @param newConfig      The new configuration to be set.
     * @return true if the new configuration is more recent than the existing one, false otherwise.
     */
    private boolean updatesConfig(
            @Nullable final ClprLedgerConfiguration existingConfig, @NonNull final ClprLedgerConfiguration newConfig) {
        // If the existing configuration is null, we are setting a new configuration.
        if (existingConfig == null) {
            return true;
        }
        // If the existing configuration is not null, we check if the new configuration is more recent.
        final var existingTime = existingConfig.timestampOrThrow();
        final var newTime = newConfig.timestampOrThrow();
        return newTime.seconds() > existingTime.seconds()
                || (newTime.seconds() == existingTime.seconds() && newTime.nanos() > existingTime.nanos());
    }
}
