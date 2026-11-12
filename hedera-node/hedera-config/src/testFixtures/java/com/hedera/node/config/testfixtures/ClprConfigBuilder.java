// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.testfixtures;

import com.hedera.node.config.data.ClprConfig;
import java.time.Duration;
import java.util.Set;

/**
 * Test-only fluent builder for {@link ClprConfig}. Each field is initialized to the
 * {@code @ConfigProperty} default declared on {@link ClprConfig}, so tests only need to
 * override the properties relevant to the scenario under test.
 */
public final class ClprConfigBuilder {
    private boolean enabled = true;
    private int protocolVersion = 1;
    private String ledgerConfigurationFile = "data/config/clprLedgerConfiguration.json";
    private String upgradeLedgerConfigurationFile = "clprLedgerConfiguration.json";
    private long minLockedStakeTinycents = 30_000_000_000_000L;
    private long stakingAccount = 803L;
    private long slashBasePenaltyTinycents = 100_000_000_000L;
    private int slashMultiplier = 2;
    private int slashBanThreshold = 5;
    private long messageExecutionCost = 1_000_000L;
    private long progressRewardTinycents = 100_000_000L;
    private int maxConcurrentSyncs = 4;
    private int syncTimeoutSeconds = 30;
    private int reputationDecaySeconds = 300;
    private long retryInitialDelayMs = 1000L;
    private long retryMaxDelayMs = 30_000L;
    private int retryMaxAttempts = 5;
    private int circuitBreakerCooldownSeconds = 120;
    private Set<String> additionalPeerEndpointCidrs = Set.of();
    private boolean syncPeerExclusionEnabled = false;
    private int connectorQueueQuotaPct = 50;
    private long sendMessageFeeTinycents = 1_000_000_000L;
    private int sendMessageIncludedBytes = 1024;
    private long verifierGasLimit = 300_000L;
    private boolean verifyProofsAtSender = true;
    private String peerEndpointsFile = "data/clpr/peer-endpoints.json";
    private long nodeSubmitBundleMaxFee = 1_000_000_000L;
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

    public ClprConfigBuilder protocolVersion(final int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public ClprConfigBuilder ledgerConfigurationFile(final String ledgerConfigurationFile) {
        this.ledgerConfigurationFile = ledgerConfigurationFile;
        return this;
    }

    public ClprConfigBuilder upgradeLedgerConfigurationFile(final String upgradeLedgerConfigurationFile) {
        this.upgradeLedgerConfigurationFile = upgradeLedgerConfigurationFile;
        return this;
    }

    public ClprConfigBuilder minLockedStakeTinycents(final long minLockedStakeTinycents) {
        this.minLockedStakeTinycents = minLockedStakeTinycents;
        return this;
    }

    public ClprConfigBuilder stakingAccount(final long stakingAccount) {
        this.stakingAccount = stakingAccount;
        return this;
    }

    public ClprConfigBuilder slashBasePenaltyTinycents(final long slashBasePenaltyTinycents) {
        this.slashBasePenaltyTinycents = slashBasePenaltyTinycents;
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

    public ClprConfigBuilder messageExecutionCost(final long messageExecutionCost) {
        this.messageExecutionCost = messageExecutionCost;
        return this;
    }

    public ClprConfigBuilder progressRewardTinycents(final long progressRewardTinycents) {
        this.progressRewardTinycents = progressRewardTinycents;
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

    public ClprConfigBuilder additionalPeerEndpointCidrs(final Set<String> additionalPeerEndpointCidrs) {
        this.additionalPeerEndpointCidrs = Set.copyOf(additionalPeerEndpointCidrs);
        return this;
    }

    public ClprConfigBuilder syncPeerExclusionEnabled(final boolean syncPeerExclusionEnabled) {
        this.syncPeerExclusionEnabled = syncPeerExclusionEnabled;
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
                protocolVersion,
                ledgerConfigurationFile,
                upgradeLedgerConfigurationFile,
                minLockedStakeTinycents,
                stakingAccount,
                slashBasePenaltyTinycents,
                slashMultiplier,
                slashBanThreshold,
                messageExecutionCost,
                progressRewardTinycents,
                maxConcurrentSyncs,
                syncTimeoutSeconds,
                reputationDecaySeconds,
                retryInitialDelayMs,
                retryMaxDelayMs,
                retryMaxAttempts,
                circuitBreakerCooldownSeconds,
                additionalPeerEndpointCidrs,
                syncPeerExclusionEnabled,
                connectorQueueQuotaPct,
                sendMessageFeeTinycents,
                sendMessageIncludedBytes,
                verifierGasLimit,
                nodeSubmitBundleMaxFee,
                verifyProofsAtSender,
                peerEndpointsFile,
                manifestGracePeriod,
                manifestGraceExtension,
                manifestMaxGraceExtensions,
                manifestSubmissionRetries,
                manifestSubmissionRetryDelay,
                manifestSubmissionDistinctTxnIds,
                10_000_000_000L);
    }
}
