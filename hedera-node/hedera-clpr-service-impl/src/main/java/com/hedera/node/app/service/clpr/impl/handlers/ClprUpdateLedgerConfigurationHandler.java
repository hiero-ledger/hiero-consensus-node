// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SEED_ENDPOINT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_TOO_MANY_SEED_ENDPOINTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CLPR_CONFIGURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.WritableLedgerConfigurationStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_UPDATE_LEDGER_CONFIGURATION} transactions.
 *
 * <p>Updates the local CLPR ledger configuration. Requires the CLPR admin key
 * (network admin / superuser). The handler preserves the immutable chain_id and
 * protocol_version (set at genesis) and auto-sets the timestamp.
 */
@Singleton
public class ClprUpdateLedgerConfigurationHandler extends AbstractClprHandler {

    /**
     * Conservative floor on the headroom max_sync_bytes must reserve above max_message_payload_bytes
     * for the proof envelope (channel leaf + one message leaf + sibling chain to block root +
     * TSS signature + ClprSyncPayload framing). Spec §1.1 explicitly warns that if max_sync_bytes
     * cannot carry a single max-sized message plus its proof overhead, the Channel deadlocks —
     * the sender can never build a bundle that fits, the peer never acks, and the queue stalls
     * forever. This is a floor, not a sizing recommendation; operators should size generously per
     * the spec's rule of thumb {@code max_message_payload_bytes * max_messages_per_bundle +
     * worst_case_proof_overhead}.
     */
    private static final long MIN_PROOF_OVERHEAD_BYTES = 65_536L;

    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprUpdateLedgerConfigurationHandler(@NonNull final ClprStateProofManager stateProofManager) {
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        final var op = body.clprUpdateLedgerConfigurationOrThrow();

        validateTruePreCheck(op.hasConfiguration(), INVALID_TRANSACTION_BODY);
        final var config = op.configurationOrThrow();

        // Validate throttles — all fields must be positive
        final var throttles = config.throttlesOrElse(ClprThrottles.DEFAULT);
        validateTruePreCheck(throttles.maxMessagesPerBundle() > 0, INVALID_CLPR_CONFIGURATION);
        validateTruePreCheck(throttles.maxMessagePayloadBytes() > 0, INVALID_CLPR_CONFIGURATION);
        validateTruePreCheck(throttles.maxQueueDepth() > 0, INVALID_CLPR_CONFIGURATION);
        validateTruePreCheck(throttles.maxGasPerMessage() > 0, INVALID_CLPR_CONFIGURATION);
        validateTruePreCheck(throttles.maxSyncBytes() > 0, INVALID_CLPR_CONFIGURATION);
        // Spec §1.1 deadlock guard: max_sync_bytes MUST leave room for one max-sized message
        // plus its proof+envelope overhead. Otherwise the first oversized message enqueued
        // stalls the Channel — sender can never assemble a bundle that fits, peer never
        // acks, queue never drains.
        validateTruePreCheck(
                throttles.maxSyncBytes() >= (long) throttles.maxMessagePayloadBytes() + MIN_PROOF_OVERHEAD_BYTES,
                INVALID_CLPR_CONFIGURATION);

        // Validate endpoints count against the supplied throttles' max_local_endpoints
        // (spec §3.10.4). Zero means no local endpoint limit is enforced.
        final var endpoints = config.endpoints();
        final int rawLimit = throttles.maxLocalEndpoints();
        if (rawLimit > 0 && endpoints.size() > rawLimit) {
            // Keeping CLPR_TOO_MANY_SEED_ENDPOINTS as the wire-level response code — the legacy
            // name reflects the previous proto field; renaming the enum is a separate concern.
            throw new PreCheckException(CLPR_TOO_MANY_SEED_ENDPOINTS);
        }

        // Validate each endpoint has required fields
        for (final var endpoint : endpoints) {
            validateEndpoint(endpoint);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // Authorization enforced by PrivilegesVerifier.checkClprAdmin — only
        // treasury and system admin accounts are permitted.
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprUpdateLedgerConfigurationOrThrow();
        final var supplied = op.configurationOrThrow();

        final var storeFactory = context.storeFactory();
        final var configStore = storeFactory.writableStore(WritableLedgerConfigurationStore.class);
        final var existing = configStore.getConfiguration();

        // Build the updated configuration, preserving immutable fields from genesis.
        // initial_trust_anchor is sourced from the latest signed snapshot's ledger_id (the
        // genesis-rooted Hiero TSS identity for this ledger) so peers registering against
        // this config see a self-describing trust anchor; if the snapshot is not yet
        // available, we preserve whatever was previously stored (empty at genesis).
        final var consensusNow = context.consensusNow();
        final var ledgerId = stateProofManager.latestLedgerId();
        final Bytes initialTrustAnchor;
        final Bytes initialTrustAnchorId;
        if (ledgerId.length() > 0) {
            initialTrustAnchor = ledgerId;
            initialTrustAnchorId = ledgerId;
        } else {
            initialTrustAnchor = existing.initialTrustAnchor();
            initialTrustAnchorId = existing.initialTrustAnchorId();
        }
        final var updatedConfig = ClprLedgerConfiguration.newBuilder()
                .protocolVersion(existing.protocolVersion())
                .chainId(existing.chainId())
                .serviceAddress(supplied.serviceAddress())
                .timestamp(toTimestamp(consensusNow))
                .throttles(supplied.throttles())
                .endpoints(supplied.endpoints())
                .initialTrustAnchor(initialTrustAnchor)
                .initialTrustAnchorId(initialTrustAnchorId)
                .build();

        configStore.put(updatedConfig);
    }

    private static void validateEndpoint(@NonNull final ClprEndpoint endpoint) throws PreCheckException {
        validateTruePreCheck(endpoint.hasServiceEndpoint(), CLPR_INVALID_SEED_ENDPOINT);
        validateTruePreCheck(!endpoint.tlsCertificate().equals(Bytes.EMPTY), CLPR_INVALID_SEED_ENDPOINT);
    }
}
