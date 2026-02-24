// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.*;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprLedgerConfigurationStore;
import org.hiero.interledger.clpr.WritableClprMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

import java.util.Objects;

/**
 * Handles the {@link  org.hiero.hapi.interledger.clpr.ClprSetLedgerConfigurationTransactionBody} to set the
 * configuration of a CLPR ledger.
 * This handler uses the {@link ClprStateProofManager} to validate the state proof and manage ledger configurations.
 */
public class ClprSetLedgerConfigurationHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ClprSetLedgerConfigurationHandler.class);
    private final ClprStateProofManager stateProofManager;
    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ClprSetLedgerConfigurationHandler(
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider) {
        this.stateProofManager = requireNonNull(stateProofManager);
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        if (!stateProofManager.clprEnabled()) {
            throw new PreCheckException(ResponseCodeEnum.NOT_SUPPORTED);
        }
        final var localLedgerId = getLocalLedgerIdOrThrow();
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
        pureChecks(txn, true);
    }

    private void pureChecks(@NonNull final TransactionBody txn, final boolean validateStateProof)
            throws PreCheckException {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        validateTruePreCheck(clprConfig.clprEnabled(), ResponseCodeEnum.NOT_SUPPORTED);
        final var localLedgerId = getLocalLedgerIdOrThrow();
        final var configTxnBdy = txn.clprSetLedgerConfiguration();
        validateTruePreCheck(configTxnBdy != null, ResponseCodeEnum.INVALID_TRANSACTION_BODY);

        validateTruePreCheck(configTxnBdy.hasLedgerConfigurationProof(), ResponseCodeEnum.INVALID_TRANSACTION);

        // Extract and validate the configuration from the state proof
        final ClprLedgerConfiguration ledgerConfig = extractConfigurationOrThrow(configTxnBdy, validateStateProof);

        final var ledgerId = ledgerConfig.ledgerIdOrThrow();
        // ledgerId must exist.
        validateTruePreCheck(ledgerId.ledgerId() != Bytes.EMPTY, ResponseCodeEnum.INVALID_TRANSACTION);
        // endpoints must not be empty.
        validateFalsePreCheck(ledgerConfig.endpoints().isEmpty(), ResponseCodeEnum.INVALID_TRANSACTION);
        // TODO: Check that certificates are non-empty and valid for each endpoint.

        final var existingConfig = stateProofManager.readLedgerConfiguration(ledgerId);
        // Guard: prevent remote/user submissions from overwriting the local ledger configuration.
        validateFalsePreCheck(
                localLedgerId.equals(ledgerId) && existingConfig != null, ResponseCodeEnum.INVALID_TRANSACTION);
        if (existingConfig != null) {
            final var existingConfigTime = existingConfig.timestampOrThrow();
            final var newConfigTime = ledgerConfig.timestampOrThrow();
            validateFalsePreCheck(
                    existingConfigTime.seconds() > newConfigTime.seconds()
                            || (existingConfigTime.seconds() == newConfigTime.seconds()
                                    && existingConfigTime.nanos() >= newConfigTime.nanos()),
                    ResponseCodeEnum.INVALID_TRANSACTION);
            return; // In all cases it is safe to update the existing ledger configuration.
        }

        // No existing configuration for this ledger id — accept it.
        // The local ledger ID is known (enforced above), so new remote configurations are allowed.
    }

    private @NonNull org.hiero.hapi.interledger.state.clpr.ClprLedgerId getLocalLedgerIdOrThrow()
            throws PreCheckException {
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        validateTruePreCheck(localLedgerId != null, ResponseCodeEnum.WAITING_FOR_LEDGER_ID);
        validateTruePreCheck(localLedgerId.ledgerId() != Bytes.EMPTY, ResponseCodeEnum.WAITING_FOR_LEDGER_ID);
        return localLedgerId;
    }

    @Override
    /**
     * Performs node-level validation for CLPR configuration submissions. The method enforces
     * dev-mode bootstrap shortcuts, verifies monotonic timestamps using the current state, and only
     * invokes proof validation when necessary. Failures are surfaced as {@link PreCheckException}s
     * so the submitting node can retry or back off without reaching handle().
     */
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // System-generated bootstrap dispatches should not fail due to state proof validation;
        // allow handle() to perform minimal checks and update local metadata.
        if (!context.isUserTransaction()) {
            return;
        }
        // All nodes need to check that the ledger configuration is an update and that the state proof is valid.
        // If any of the pure checks fail, the transaction will not be processed and the submitting nodes will need
        // to be held accountable for the failure.
        final var txn = context.body();
        final var submittingNode = context.creatorInfo();
        final var selfNodeId = networkInfo.selfNodeInfo().nodeId();
        try {
            // TODO: This call needs to make sure that if it fails, it will always fail in the future.
            //       Anything that can fail temporarily must pass and then fail on the handle thread.
            pureChecks(txn, context.isUserTransaction());
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

        // Extract the configuration from the state proof (already validated in pureChecks)
        final ClprLedgerConfiguration newConfig;
        try {
            newConfig = ClprStateProofUtils.extractConfiguration(configTxn.ledgerConfigurationProofOrThrow());
        } catch (final IllegalArgumentException | IllegalStateException e) {
            // This should not happen as the state proof was already validated in pureChecks
            throw new HandleException(ResponseCodeEnum.CLPR_INVALID_STATE_PROOF);
        }

        // Persist local metadata alongside the configuration update. LedgerId is set exclusively by the
        // HandleWorkflow bridge after genesis proof completion; this handler only preserves existing values.
        final var storeFactory = context.storeFactory();
        final var rosterStore = storeFactory.readableStore(ReadableRosterStore.class);
        final var metadataStore = storeFactory.writableStore(WritableClprMetadataStore.class);
        final var activeRosterHash = requireNonNull(
                rosterStore.getCurrentRosterHash(), "active roster hash must be present for CLPR updates");
        final var existingMetadata = metadataStore.get();
        final var tssConfig = configProvider.getConfiguration().getConfigData(TssConfig.class);
        // The authoritative local ledgerId comes from the HandleWorkflow WRAPS-history bridge.
        // Only use dev-mode fallback from the incoming config when history is disabled.
        final var allowDevFallbackLedgerId = stateProofManager.isDevModeEnabled() && !tssConfig.historyEnabled();
        final var existingLedgerId = existingMetadata != null ? existingMetadata.ledgerId() : null;
        final var metadataLedgerId =
                existingLedgerId != null ? existingLedgerId : (allowDevFallbackLedgerId ? newConfig.ledgerId() : null);

        final var ledgerId = newConfig.ledgerIdOrThrow();
        final var configStore = context.storeFactory().writableStore(WritableClprLedgerConfigurationStore.class);
        final var existingConfig = configStore.get(ledgerId);
        final var endpointVisibilityChanged = endpointVisibilityChanged(existingConfig, newConfig);
        final var existingRosterHash = existingMetadata != null ? existingMetadata.rosterHash() : null;
        final var shouldUpdateConfig = updatesConfig(existingConfig, newConfig) || endpointVisibilityChanged;
        final var metadataChanged = existingMetadata == null
                || existingMetadata.ledgerId() == null
                || existingMetadata.rosterHash() == null
                || !existingMetadata.ledgerId().equals(metadataLedgerId)
                || !existingMetadata.rosterHash().equals(activeRosterHash);
        log.warn(
                "CLPR handler: configLedgerId={} metadataLedgerId={} existingMeta={} existingConfig={}"
                        + " metadataChanged={} shouldUpdateConfig={} activeRosterHash={}",
                ledgerId,
                metadataLedgerId,
                existingMetadata != null ? existingMetadata.ledgerId() : "null",
                existingConfig != null,
                metadataChanged,
                shouldUpdateConfig,
                activeRosterHash);
        if (!metadataChanged && !shouldUpdateConfig) {
            return;
        }
        if (metadataChanged) {
            log.warn("CLPR handler: storing metadata with ledgerId={}", metadataLedgerId);
            metadataStore.put(ClprLocalLedgerMetadata.newBuilder()
                    .ledgerId(metadataLedgerId)
                    .rosterHash(activeRosterHash)
                    .build());
        }
        if (shouldUpdateConfig) {
            log.warn("CLPR handler: storing config with key={}", newConfig.ledgerId());
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

    private static boolean endpointVisibilityChanged(
            @Nullable final ClprLedgerConfiguration existingConfig, @NonNull final ClprLedgerConfiguration newConfig) {
        if (existingConfig == null) {
            return true;
        }
        return hasPublicizedEndpoints(existingConfig) != hasPublicizedEndpoints(newConfig);
    }

    private static boolean hasPublicizedEndpoints(@NonNull final ClprLedgerConfiguration config) {
        final var endpoints = config.endpoints();
        if (endpoints.isEmpty()) {
            return false;
        }
        return endpoints.stream().allMatch(org.hiero.hapi.interledger.state.clpr.ClprEndpoint::hasEndpoint);
    }

    @NonNull
    private ClprLedgerConfiguration extractConfigurationOrThrow(
            @NonNull final ClprSetLedgerConfigurationTransactionBody txn, final boolean validateStateProof)
            throws PreCheckException {
        try {
            if (validateStateProof && !stateProofManager.validateStateProof(txn)) {
                throw new PreCheckException(ResponseCodeEnum.CLPR_INVALID_STATE_PROOF);
            }
            return ClprStateProofUtils.extractConfiguration(txn.ledgerConfigurationProofOrThrow());
        } catch (final IllegalArgumentException | IllegalStateException e) {
            throw new PreCheckException(ResponseCodeEnum.CLPR_INVALID_STATE_PROOF);
        }
    }
}
