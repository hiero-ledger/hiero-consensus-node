// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.testfixtures;

import com.hedera.node.config.data.ClprConfig;
import java.time.Duration;

/**
 * Test-only fluent builder for {@link ClprConfig}. Each field is initialized to the
 * {@code @ConfigProperty} default declared on {@link ClprConfig}, so tests only need to
 * override the properties relevant to the scenario under test.
 */
public final class ClprConfigBuilder {
    private boolean enabled = true;
    private String chainId = "hiero:localnet";
    private int protocolVersion = 1;
    private long minLockedStake = 100_000_000L;
    private long stakingAccount = 803L;
    private long slashBasePenalty = 10_000_000L;
    private int slashMultiplier = 2;
    private int slashBanThreshold = 5;
    private long endpointPayoutCapTinybars = 5_000_000L;
    private long endpointMisbehaviorPenaltyTinybars = 5_000_000L;
    private long messageExecutionCost = 1_000_000L;
    private int endpointMarginPercent = 10;
    private int maxConcurrentSyncs = 4;
    private int syncTimeoutSeconds = 30;
    private int reputationDecaySeconds = 300;
    private long retryInitialDelayMs = 1000L;
    private long retryMaxDelayMs = 30_000L;
    private int retryMaxAttempts = 5;
    private int circuitBreakerCooldownSeconds = 120;
    private boolean syncPeerExclusionEnabled = false;
    private int discoveryIntervalSeconds = 300;
    private int connectorQueueQuotaPct = 50;
    private long verifierGasLimit = 300_000L;
    private boolean verifyProofsAtSender = true;
    private String peerEndpointsFile = "data/clpr/peer-endpoints.json";
    private long nodeSubmitBundleMaxFee = 1_000_000_000L;
    private String caCrtPath = "";
    private String caKeyPath = "";
    private int mtlsPort = 50214;
    private boolean endpointManifestEnabled = false;
    private Duration manifestGracePeriod = Duration.ofSeconds(300);
    private Duration manifestGraceExtension = Duration.ofSeconds(300);
    private int manifestMaxGraceExtensions = 2;
    private int manifestSubmissionRetries = 50;
    private Duration manifestSubmissionRetryDelay = Duration.ofSeconds(5);
    private int manifestSubmissionDistinctTxnIds = 10;

    private ClprConfigBuilder() {}

    public static ClprConfigBuilder newBuilder() {
        return new ClprConfigBuilder();
    }

    public ClprConfigBuilder enabled(final boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public ClprConfigBuilder endpointManifestEnabled(final boolean endpointManifestEnabled) {
        this.endpointManifestEnabled = endpointManifestEnabled;
        return this;
    }

    public ClprConfigBuilder chainId(final String chainId) {
        this.chainId = chainId;
        return this;
    }

    public ClprConfigBuilder protocolVersion(final int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public ClprConfigBuilder minLockedStake(final long minLockedStake) {
        this.minLockedStake = minLockedStake;
        return this;
    }

    public ClprConfigBuilder stakingAccount(final long stakingAccount) {
        this.stakingAccount = stakingAccount;
        return this;
    }

    public ClprConfigBuilder slashBasePenalty(final long slashBasePenalty) {
        this.slashBasePenalty = slashBasePenalty;
        return this;
    }

    public ClprConfigBuilder slashMultiplier(final int slashMultiplier) {
        this.slashMultiplier = slashMultiplier;
        return this;
    }

    public ClprConfigBuilder slashBanThreshold(final int slashBanThreshold) {
        this.slashBanThreshold = slashBanThreshold;
        return this;
    }

    public ClprConfigBuilder endpointPayoutCapTinybars(final long endpointPayoutCapTinybars) {
        this.endpointPayoutCapTinybars = endpointPayoutCapTinybars;
        return this;
    }

    public ClprConfigBuilder endpointMisbehaviorPenaltyTinybars(final long endpointMisbehaviorPenaltyTinybars) {
        this.endpointMisbehaviorPenaltyTinybars = endpointMisbehaviorPenaltyTinybars;
        return this;
    }

    public ClprConfigBuilder messageExecutionCost(final long messageExecutionCost) {
        this.messageExecutionCost = messageExecutionCost;
        return this;
    }

    public ClprConfigBuilder endpointMarginPercent(final int endpointMarginPercent) {
        this.endpointMarginPercent = endpointMarginPercent;
        return this;
    }

    public ClprConfigBuilder maxConcurrentSyncs(final int maxConcurrentSyncs) {
        this.maxConcurrentSyncs = maxConcurrentSyncs;
        return this;
    }

    public ClprConfigBuilder syncTimeoutSeconds(final int syncTimeoutSeconds) {
        this.syncTimeoutSeconds = syncTimeoutSeconds;
        return this;
    }

    public ClprConfigBuilder reputationDecaySeconds(final int reputationDecaySeconds) {
        this.reputationDecaySeconds = reputationDecaySeconds;
        return this;
    }

    public ClprConfigBuilder retryInitialDelayMs(final long retryInitialDelayMs) {
        this.retryInitialDelayMs = retryInitialDelayMs;
        return this;
    }

    public ClprConfigBuilder retryMaxDelayMs(final long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
        return this;
    }

    public ClprConfigBuilder retryMaxAttempts(final int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
        return this;
    }

    public ClprConfigBuilder circuitBreakerCooldownSeconds(final int circuitBreakerCooldownSeconds) {
        this.circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds;
        return this;
    }

    public ClprConfigBuilder syncPeerExclusionEnabled(final boolean syncPeerExclusionEnabled) {
        this.syncPeerExclusionEnabled = syncPeerExclusionEnabled;
        return this;
    }

    public ClprConfigBuilder discoveryIntervalSeconds(final int discoveryIntervalSeconds) {
        this.discoveryIntervalSeconds = discoveryIntervalSeconds;
        return this;
    }

    public ClprConfigBuilder connectorQueueQuotaPct(final int connectorQueueQuotaPct) {
        this.connectorQueueQuotaPct = connectorQueueQuotaPct;
        return this;
    }

    public ClprConfigBuilder verifierGasLimit(final long verifierGasLimit) {
        this.verifierGasLimit = verifierGasLimit;
        return this;
    }

    public ClprConfigBuilder nodeSubmitBundleMaxFee(final long nodeSubmitBundleMaxFee) {
        this.nodeSubmitBundleMaxFee = nodeSubmitBundleMaxFee;
        return this;
    }

    public ClprConfigBuilder verifyProofsAtSender(final boolean verifyProofsAtSender) {
        this.verifyProofsAtSender = verifyProofsAtSender;
        return this;
    }

    public ClprConfigBuilder peerEndpointsFile(final String peerEndpointsFile) {
        this.peerEndpointsFile = peerEndpointsFile;
        return this;
    }

    public ClprConfigBuilder caCrtPath(final String caCrtPath) {
        this.caCrtPath = caCrtPath;
        return this;
    }

    public ClprConfigBuilder caKeyPath(final String caKeyPath) {
        this.caKeyPath = caKeyPath;
        return this;
    }

    public ClprConfigBuilder mtlsPort(final int mtlsPort) {
        this.mtlsPort = mtlsPort;
        return this;
    }

    public ClprConfigBuilder manifestGracePeriod(final Duration manifestGracePeriod) {
        this.manifestGracePeriod = manifestGracePeriod;
        return this;
    }

    public ClprConfigBuilder manifestGraceExtension(final Duration manifestGraceExtension) {
        this.manifestGraceExtension = manifestGraceExtension;
        return this;
    }

    public ClprConfigBuilder manifestMaxGraceExtensions(final int manifestMaxGraceExtensions) {
        this.manifestMaxGraceExtensions = manifestMaxGraceExtensions;
        return this;
    }

    public ClprConfigBuilder manifestSubmissionRetries(final int manifestSubmissionRetries) {
        this.manifestSubmissionRetries = manifestSubmissionRetries;
        return this;
    }

    public ClprConfigBuilder manifestSubmissionRetryDelay(final Duration manifestSubmissionRetryDelay) {
        this.manifestSubmissionRetryDelay = manifestSubmissionRetryDelay;
        return this;
    }

    public ClprConfigBuilder manifestSubmissionDistinctTxnIds(final int manifestSubmissionDistinctTxnIds) {
        this.manifestSubmissionDistinctTxnIds = manifestSubmissionDistinctTxnIds;
        return this;
    }

    public ClprConfig build() {
        return new ClprConfig(
                enabled,
                chainId,
                protocolVersion,
                minLockedStake,
                stakingAccount,
                slashBasePenalty,
                slashMultiplier,
                slashBanThreshold,
                endpointPayoutCapTinybars,
                endpointMisbehaviorPenaltyTinybars,
                messageExecutionCost,
                endpointMarginPercent,
                maxConcurrentSyncs,
                syncTimeoutSeconds,
                reputationDecaySeconds,
                retryInitialDelayMs,
                retryMaxDelayMs,
                retryMaxAttempts,
                circuitBreakerCooldownSeconds,
                syncPeerExclusionEnabled,
                discoveryIntervalSeconds,
                connectorQueueQuotaPct,
                verifierGasLimit,
                nodeSubmitBundleMaxFee,
                verifyProofsAtSender,
                peerEndpointsFile,
                caCrtPath,
                caKeyPath,
                mtlsPort,
                endpointManifestEnabled,
                manifestGracePeriod,
                manifestGraceExtension,
                manifestMaxGraceExtensions,
                manifestSubmissionRetries,
                manifestSubmissionRetryDelay,
                manifestSubmissionDistinctTxnIds);
    }
}
