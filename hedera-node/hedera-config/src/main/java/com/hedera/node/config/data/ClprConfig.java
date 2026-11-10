// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 * Configuration for the CLPR (Cross Ledger Protocol) service.
 */
@ConfigData("clpr")
public record ClprConfig(
        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean enabled,

        @ConfigProperty(defaultValue = "1") @NetworkProperty int protocolVersion,

        // --- Ledger configuration throttles (loaded like throttles.json) ---
        // Node-local ClprLedgerConfiguration file (protobuf JSON, throttles only) that seeds the throttles at
        // genesis; falls back to the bundled clpr/clprLedgerConfiguration.json resource if absent or invalid.
        // Every node must have an identical file.
        @ConfigProperty(defaultValue = "data/config/clprLedgerConfiguration.json") @NodeProperty
        String ledgerConfigurationFile,
        // Name of the file in networkAdmin.upgradeSysFilesLoc whose throttles are applied at each upgrade
        // with a synthetic ClprUpdateLedgerConfiguration, when they differ from the current ones.
        @ConfigProperty(defaultValue = "clprLedgerConfiguration.json")
        String upgradeLedgerConfigurationFile,

        // --- Connector bonds and penalties ---
        // Minimum connector bond ($3,000), converted to tinybars at the active consensus exchange rate
        // when completing registration. Existing locked stake remains denominated in tinybars.
        @ConfigProperty(defaultValue = "30000000000000") @Min(0) @NetworkProperty
        long minLockedStakeTinycents,

        @ConfigProperty(defaultValue = "803") @NetworkProperty
        long stakingAccount,

        // First slash of a Connector ($10 at $0.10/ℏ), multiplied by slashMultiplier until slashBanThreshold.
        // The submitting endpoint receives each penalty in full, up to the staking account's balance.
        @ConfigProperty(defaultValue = "10000000000") @NetworkProperty
        long slashBasePenalty,

        @ConfigProperty(defaultValue = "2") @NetworkProperty int slashMultiplier,
        @ConfigProperty(defaultValue = "5") @NetworkProperty int slashBanThreshold,

        // Penalty charged to an endpoint whose bundle fails verification
        @ConfigProperty(defaultValue = "5000000") @NetworkProperty
        long endpointMisbehaviorPenaltyTinybars,

        @ConfigProperty(defaultValue = "1000000") @NetworkProperty
        long messageExecutionCost,

        // Flat reward per newly dispatched data message ($0.01 at $0.10/ℏ).
        @ConfigProperty(defaultValue = "10000000") @Min(0) @NetworkProperty
        long progressRewardTinybars,
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
        // --- Queue monopolization protection (CLPR-3.5) ---
        @ConfigProperty(defaultValue = "50") @NetworkProperty
        int connectorQueueQuotaPct,

        // --- sendMessage system contract price ---
        // Price of a sendMessage call in tinycents ($0.10), including its gas; the caller attaches the rest as value
        @ConfigProperty(defaultValue = "1000000000") @NetworkProperty
        long sendMessageFeeTinycents,
        // Message data bytes covered by sendMessageFeeTinycents; each further byte is priced at the STATE_BYTES rate
        @ConfigProperty(defaultValue = "1024") @NetworkProperty
        int sendMessageIncludedBytes,

        // --- Verifier contract dispatch (CLPR-5.3) ---
        // The dispatched call's gas must cover the EIP-7623 calldata floor (~40 gas per proof byte); a Hiero
        // state proof carrying a WRAPS proof is ~15 KB, so even a single-message bundle needs ~640K gas
        @ConfigProperty(defaultValue = "2000000") @NetworkProperty
        long verifierGasLimit,

        // --- Node-submitted ClprSubmitBundle fee cap (tinybars) ---
        // Maximum fee the node will pay when submitting an inbound bundle for consensus. A bundle is exempt from node
        // and network fees and pays $0.01 + PROCESSING_BYTES per bundle byte + verifier gas, so a bundle at the
        // default 4 MiB max_sync_bytes costs about $0.46 at the proposal's byte and gas rates.
        @ConfigProperty(defaultValue = "100000000000") @NetworkProperty
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
        int manifestSubmissionDistinctTxnIds,

        // Maximum fee offered by a node for its endpoint self-publication, in tinybars.
        @ConfigProperty(defaultValue = "10000000000") long manifestSubmissionMaxFee) {}
