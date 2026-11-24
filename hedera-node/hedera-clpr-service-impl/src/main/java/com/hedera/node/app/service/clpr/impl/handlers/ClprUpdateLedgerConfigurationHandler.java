// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.areValidThrottles;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.capDeliveryLimits;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.preserveAdvertisedLimits;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CLPR_SERVICE_ADDRESS;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.WritableLedgerConfigurationStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_UPDATE_LEDGER_CONFIGURATION} transactions.
 *
 * <p>Updates the local CLPR ledger configuration. Requires an authorized CLPR admin payer.
 * The handler preserves the chain_id and protocol_version (set at genesis) and auto-sets the
 * timestamp. Only post-upgrade setup changes the chain_id, once, replacing an ID outside the
 * {@code eip155} namespace (such as the 0.79 default {@code hiero:localnet}) with
 * {@code eip155:<contracts.chainId>}. The service_address
 * must be the CLPR system contract's EVM address ({@code 0x16e}, the value this ledger's
 * endpoint manifest carries) and defaults to it when omitted. The max_gas_per_message throttle
 * must not exceed {@code contracts.maxGasPerTransaction} (network property defined in
 * {@link ContractsConfig#maxGasPerTransaction()}).
 */
@Singleton
public class ClprUpdateLedgerConfigurationHandler extends AbstractClprHandler {

    @Inject
    public ClprUpdateLedgerConfigurationHandler() {}

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        final var op = body.clprUpdateLedgerConfigurationOrThrow();

        validateTruePreCheck(op.hasConfiguration(), INVALID_TRANSACTION_BODY);
        final var config = op.configurationOrThrow();

        // A peer's verifier (e.g. the native Hiero TSS verifier) requires config.service_address ==
        // manifest.service_address (spec §4.8), and this ledger's endpoint manifest fixes
        // service_address at genesis to the CLPR system contract address. Any other value would make
        // this ledger unverifiable by its peers, so reject it; an empty value is allowed and
        // defaults to that address in doHandle.
        final var serviceAddress = config.serviceAddress();
        validateTruePreCheck(
                serviceAddress.length() == 0 || serviceAddress.equals(CLPR_SERVICE_ADDRESS),
                INVALID_CLPR_CONFIGURATION);

        // Validate throttles — all fields must be positive and max_sync_bytes must leave room for one
        // max-sized message plus its proof overhead
        validateTruePreCheck(
                areValidThrottles(config.throttlesOrElse(ClprThrottles.DEFAULT)), INVALID_CLPR_CONFIGURATION);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // PrivilegesVerifier authorizes the treasury, system admin, and configured CLPR admin.
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprUpdateLedgerConfigurationOrThrow();
        final var supplied = op.configurationOrThrow();
        final var requested = supplied.throttlesOrThrow();

        // Inbound messages are dispatched as contract calls with up to max_gas_per_message gas, and a
        // call above contracts.maxGasPerTransaction is refused. Reject such a value instead of storing
        // and advertising a per-message budget this ledger can never grant.
        final var maxGasPerTransaction =
                context.configuration().getConfigData(ContractsConfig.class).maxGasPerTransaction();
        validateTrue(requested.maxGasPerMessage() <= maxGasPerTransaction, INVALID_CLPR_CONFIGURATION);

        final var storeFactory = context.storeFactory();
        final var configStore = storeFactory.writableStore(WritableLedgerConfigurationStore.class);
        final var existing = configStore.getConfiguration();

        final var current = existing.throttlesOrElse(ClprThrottles.DEFAULT);
        final int queueDepthCeiling =
                context.configuration().getConfigData(ClprConfig.class).maxQueueDepthCeiling();
        validateTrue(
                preserveAdvertisedLimits(capDeliveryLimits(requested, queueDepthCeiling), current)
                        .equals(requested),
                INVALID_CLPR_CONFIGURATION);

        // Build the updated configuration, preserving immutable fields from genesis.
        // Source the trust anchor from this transaction's consensus state. Signed snapshot
        // availability is node-local and must never decide a state write. Until the history
        // ledger id is established, preserve the prior anchors (empty at genesis).
        final var consensusNow = context.consensusNow();
        final var ledgerId = context.historyLedgerId();
        final Bytes initialTrustAnchor;
        final Bytes initialTrustAnchorId;
        if (ledgerId.length() > 0) {
            initialTrustAnchor = ledgerId;
            initialTrustAnchorId = ledgerId;
        } else {
            initialTrustAnchor = existing.initialTrustAnchor();
            initialTrustAnchorId = existing.initialTrustAnchorId();
        }
        // An omitted service_address defaults to the CLPR system contract address (pureChecks
        // rejects any other value; see there for why).
        final var serviceAddress =
                supplied.serviceAddress().length() > 0 ? supplied.serviceAddress() : CLPR_SERVICE_ADDRESS;
        final var updatedConfig = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(existing.protocolVersion())
                .chainId(existing.chainId())
                .serviceAddress(serviceAddress)
                .timestamp(toTimestamp(consensusNow))
                .throttles(supplied.throttles())
                .initialTrustAnchor(initialTrustAnchor)
                .initialTrustAnchorId(initialTrustAnchorId)
                .build();

        configStore.put(updatedConfig);
    }
}
