// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;
import java.util.Set;

/**
 * Configuration for the CLPR (Cross Ledger Protocol) service.
 */
@ConfigData("clpr")
public record ClprConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean enabled,

        @ConfigProperty(defaultValue = "hiero:mainnet") @NetworkProperty
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

        // Flat reward Y paid to a bundle submitter for each bundle item that makes progress, in tinycents
        // (clpr-spec PR #65 §3.2). Billed to the bundle's Connectors in proportion to their message counts.
        @ConfigProperty(defaultValue = "100000000") @NetworkProperty
        long progressRewardTinycents,
        // --- Sync orchestration (CLPR-4.3) ---
        @ConfigProperty(defaultValue = "4") int maxConcurrentSyncs,
        // Deadline for a whole outbound sync exchange; each read or write must also finish within
        // syncReadTimeoutSeconds, so a peer that stops answering frees its sync slot quickly
        @ConfigProperty(defaultValue = "15") int syncTimeoutSeconds,
        @ConfigProperty(defaultValue = "5") int syncReadTimeoutSeconds,
        @ConfigProperty(defaultValue = "300") int reputationDecaySeconds,
        @ConfigProperty(defaultValue = "1000") long retryInitialDelayMs,
        @ConfigProperty(defaultValue = "30000") long retryMaxDelayMs,
        @ConfigProperty(defaultValue = "5") @NetworkProperty int retryMaxAttempts,
        @ConfigProperty(defaultValue = "120") int circuitBreakerCooldownSeconds,
        // --- Local peer exclusion heuristics ---
        // When false, CLPR does not reject inbound requests or remove outbound candidates
        // due to local shunning or open circuit-breaker state.
        @ConfigProperty(defaultValue = "true") boolean syncPeerExclusionEnabled,
        // --- Queue monopolization protection (CLPR-3.5) ---
        @ConfigProperty(defaultValue = "50") @NetworkProperty
        int connectorQueueQuotaPct,

        // --- Verifier contract dispatch (CLPR-5.3) ---
        // The dispatched call's gas must cover the EIP-7623 calldata floor (~40 gas per proof byte); a Hiero
        // state proof carrying a WRAPS proof is ~15 KB, so even a single-message bundle needs ~640K gas
        @ConfigProperty(defaultValue = "2000000") @NetworkProperty
        long verifierGasLimit,

        // --- Node-submitted ClprSubmitBundle fee cap (tinybars) ---
        // Maximum fee the node will pay when submitting an inbound bundle for consensus.
        @ConfigProperty(defaultValue = "1000000000") @NetworkProperty
        long nodeSubmitBundleMaxFee,

        // --- Verifier output limits (untrusted: a Channel's verifier is chosen by whoever completes it) ---
        // Whether peer endpoints may use loopback, private, link-local, or other non-public addresses. Every node
        // dials a Channel's peer endpoints, so this is off on public networks; development and test networks,
        // whose peers run on loopback or private addresses, turn it on. Literal IP addresses and ports in 1-65535
        // are required either way.
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean allowNonPublicPeerEndpoints,

        // Maximum serialized size of a Channel record, which holds the verifier-returned trust anchor, endpoint
        // manifest, and peer configuration. Bounds the state a Channel's verifier can make every node store. An
        // Ethereum Channel's record is about 50 KB (its anchor carries the full sync committee), so this leaves room
        // for that plus a full endpoint manifest.
        @ConfigProperty(defaultValue = "98304") @NetworkProperty
        int maxChannelStateBytes,

        // Maximum number of Channels in state, closed ones included; registering or completing a Channel fails once
        // it is reached. Bounds the state and sync work that permissionless Channel creation can cause.
        @ConfigProperty(defaultValue = "10000") @NetworkProperty
        long maxChannels,

        // --- Operator controls (node-local, not consensus) ---
        // Channel ids (hex) this node declines to serve: it neither syncs them nor answers their inbound syncs.
        @ConfigProperty(defaultValue = "") @NodeProperty Set<String> deniedChannelIds,

        // Peer endpoints this node never dials, as an IP address (any port) or ip:port ([ipv6]:port for IPv6).
        @ConfigProperty(defaultValue = "") @NodeProperty Set<String> deniedPeerEndpoints,

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
        int manifestSubmissionDistinctTxnIds) {}
