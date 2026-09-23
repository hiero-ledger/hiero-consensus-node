// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for the CLPR (Cross Ledger Protocol) service.
 */
@ConfigData("clpr")
public record ClprConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean enabled,

        @ConfigProperty(defaultValue = "hiero:localnet") @NetworkProperty
        String chainId,

        @ConfigProperty(defaultValue = "1") @NetworkProperty int protocolVersion,

        @ConfigProperty(defaultValue = "100000000") @NetworkProperty
        long minLockedStake,

        @ConfigProperty(defaultValue = "803") @NetworkProperty
        long stakingAccount,

        @ConfigProperty(defaultValue = "10000000") @NetworkProperty
        long slashBasePenalty,

        @ConfigProperty(defaultValue = "2") @NetworkProperty int slashMultiplier,
        @ConfigProperty(defaultValue = "5") @NetworkProperty int slashBanThreshold,

        @ConfigProperty(defaultValue = "5000000") @NetworkProperty
        long endpointPayoutCapTinybars,

        @ConfigProperty(defaultValue = "5000000") @NetworkProperty
        long endpointMisbehaviorPenaltyTinybars,

        @ConfigProperty(defaultValue = "1000000") @NetworkProperty
        long messageExecutionCost,

        @ConfigProperty(defaultValue = "10") @NetworkProperty
        int endpointMarginPercent,
        // --- Sync orchestration (CLPR-4.3) ---
        @ConfigProperty(defaultValue = "4") int maxConcurrentSyncs,
        @ConfigProperty(defaultValue = "30") int syncTimeoutSeconds,
        @ConfigProperty(defaultValue = "300") int reputationDecaySeconds,
        @ConfigProperty(defaultValue = "1000") long retryInitialDelayMs,
        @ConfigProperty(defaultValue = "30000") long retryMaxDelayMs,
        @ConfigProperty(defaultValue = "5") @NetworkProperty int retryMaxAttempts,
        @ConfigProperty(defaultValue = "120") int circuitBreakerCooldownSeconds,
        // --- Local peer exclusion heuristics ---
        // When false, CLPR does not reject inbound requests or remove outbound candidates
        // due to local shunning or open circuit-breaker state.
        @ConfigProperty(defaultValue = "false") boolean syncPeerExclusionEnabled,
        // --- Peer endpoint discovery (CLPR-7.2) ---
        // Period (seconds) between outbound discoverEndpoints calls. Set to 0 to disable.
        @ConfigProperty(defaultValue = "300") int discoveryIntervalSeconds,
        // --- Queue monopolization protection (CLPR-3.5) ---
        @ConfigProperty(defaultValue = "50") @NetworkProperty
        int connectorQueueQuotaPct,

        // --- Verifier contract dispatch (CLPR-5.3) ---
        @ConfigProperty(defaultValue = "300000") @NetworkProperty
        long verifierGasLimit,

        // --- Node-submitted ClprSubmitBundle fee cap (tinybars) ---
        // Maximum fee the node will pay when submitting an inbound bundle for consensus.
        @ConfigProperty(defaultValue = "1000000000") @NetworkProperty
        long nodeSubmitBundleMaxFee,

        // --- Sender-side state-proof verification (bring-up sanity check) ---
        // When true, re-parses every freshly built bundle/config StateProof and verifies its TSS
        // signature against the recomputed block root before submitting the bytes to the network
        // (assertValidBundleOrNull / assertValidOrEmpty). Useful during bring-up to fail loudly
        // on a buggy proof builder; can be turned off in steady state to save CPU since the
        // receiver verifies independently.
        @ConfigProperty(defaultValue = "true") boolean verifyProofsAtSender,

        // --- Restart rehydration (node-local, CLPR-4.3) ---
        // Node-local file recording the outbound sync orchestrator's per-channel peer endpoints
        // (and the channel ids themselves), so a restarted node can re-register channels and
        // re-seed endpoints without iterating consensus state. Path is relative to the node working
        // directory; serialized as JSON. This is NOT consensus state.
        @ConfigProperty(defaultValue = "data/clpr/peer-endpoints.json")
        String peerEndpointsFile,

        // --- mTLS (CLPR-4.4) ---
        // Path to the ECDSA P-384 CA certificate (PEM) published in ClprEndpoint.tls_certificate to peers.
        // Empty = mTLS not configured; node falls back to plaintext and logs a warning.
        @ConfigProperty(defaultValue = "") String caCrtPath,
        // Path to the ECDSA P-384 CA private key, unencrypted: PEM (PKCS#8 or SEC1/PKCS#1) or binary DER
        // PKCS#8. See docs/design/services/clpr-service/mtls.md ("CA key and cert encodings") for details.
        // Must be set alongside caCrtPath; ignored if caCrtPath is empty.
        @ConfigProperty(defaultValue = "") String caKeyPath,
        // Port on which this node serves the mTLS-protected CLPR endpoints (currently the sync endpoint)
        // when mTLS is enabled (caCrtPath/caKeyPath set).
        @ConfigProperty(defaultValue = "50214") @NodeProperty @Min(0) @Max(65535)
        int mtlsPort,

        // --- Endpoint manifest master feature flag ---
        // Gates the node-driven endpoint-manifest lifecycle: the reconciler that opens
        // constructions, the ClprEndpointPublicationHandler that routes publications into the
        // active construction, and downstream consumers (peer verifier dual-ABI dispatch, bundle
        // Step 1b apply, sync-orchestrator staleness signalling). Default off — turned on by
        // network governance once every peer verifier contract has migrated to the manifest-aware
        // ABI. When off, the reconciler is a no-op, publications are dropped with an info log,
        // and no construction singleton is ever written to state.
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean endpointManifestEnabled,

        // --- Endpoint manifest construction lifecycle (design doc §8.1) ---
        // Grace period, extension size, and extension budget for the manifest construction that
        // gathers per-node endpoint publications. Defaults mirror the TssConfig envelope so day-one
        // behavior matches the well-tested TSS bootstrap window; independent knobs let CLPR tune
        // without perturbing TSS.
        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration manifestGracePeriod,

        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration manifestGraceExtension,

        @ConfigProperty(defaultValue = "2") @NetworkProperty int manifestMaxGraceExtensions,

        // --- Endpoint publication submission (design doc §8.2) ---
        // Retry knobs for a node's outbound ClprEndpointPublication. Defaults mirror
        // NetworkAdminConfig.{timesToTrySubmission, retryDelay, distinctTxnIdsToTry} so behavior
        // matches the retry envelope TssSubmissions already relies on.
        @ConfigProperty(defaultValue = "50") @NetworkProperty
        int manifestSubmissionRetries,

        @ConfigProperty(defaultValue = "5s") @NetworkProperty
        Duration manifestSubmissionRetryDelay,

        @ConfigProperty(defaultValue = "10") @NetworkProperty
        int manifestSubmissionDistinctTxnIds) {}
