// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.platform.system.Platform;

/**
 * Statistics for operations on {@link NftLedger}.
 */
public class NftLedgerStatistics {

    private static final String NFT_CATEGORY = "NFT";

    /**
     * default half-life for statistics
     */
    private static final double DEFAULT_HALF_LIFE = 10;

    /**
     * avg time taken to mint a token in microseconds
     */
    private static final RunningAverageMetric.Config MINT_TOKEN_MICRO_SEC_CONFIG = new RunningAverageMetric.Config(
                    NFT_CATEGORY, "nftMintedTokenMicroSec")
            .withDescription("avg time taken to execute the NftLedger mintToken method (in microseconds)")
            .withHalfLife(DEFAULT_HALF_LIFE);

    private static RunningAverageMetric mintTokenMicroSec;

    /**
     * avg time taken to transfer a token in microseconds
     */
    private static final RunningAverageMetric.Config TRANSFER_TOKEN_MICRO_SEC_CONFIG = new RunningAverageMetric.Config(
                    NFT_CATEGORY, "nftTransferTokenMicroSec")
            .withDescription("avg time taken to execute the NftLedger transferToken method (in microseconds)")
            .withHalfLife(DEFAULT_HALF_LIFE);

    private static RunningAverageMetric transferTokenMicroSec;

    /**
     * avg time taken to burn a token in microseconds
     */
    private static final RunningAverageMetric.Config BURN_TOKEN_MICRO_SEC_CONFIG = new RunningAverageMetric.Config(
                    NFT_CATEGORY, "nftBurnTokenMicroSec")
            .withDescription("avg time taken to execute the NftLedger burnToken method (in microseconds)")
            .withHalfLife(DEFAULT_HALF_LIFE);

    private static RunningAverageMetric burnTokenMicroSec;

    private static final SpeedometerMetric.Config MINTED_TOKENS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    NFT_CATEGORY, "mintedTokensPerSecond")
            .withDescription("number of NFTs minted per second")
            .withFormat(FORMAT_9_6);
    private static SpeedometerMetric mintedTokensPerSecond;

    private static final SpeedometerMetric.Config TRANSFERRED_TOKENS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    NFT_CATEGORY, "transferredTokensPerSecond")
            .withDescription("number of NFTs transferred per second")
            .withFormat(FORMAT_9_6);
    private static SpeedometerMetric transferredTokensPerSecond;

    private static final SpeedometerMetric.Config BURNED_TOKENS_PER_SECOND_CONFIG = new SpeedometerMetric.Config(
                    NFT_CATEGORY, "burnedTokensPerSecond")
            .withDescription("number of NFTs burned per second")
            .withFormat(FORMAT_9_6);
    private static SpeedometerMetric burnedTokensPerSecond;

    private static boolean registered;

    private NftLedgerStatistics() {}

    protected static void recordBurnTokenDuration(final long value) {
        if (registered) {
            burnTokenMicroSec.update(value);
        }
    }

    protected static void recordMintTokenDuration(final long value) {
        if (registered) {
            mintTokenMicroSec.update(value);
        }
    }

    protected static void recordTransferTokenDuration(final long value) {
        if (registered) {
            transferTokenMicroSec.update(value);
        }
    }

    protected static void addBurnTokenOperation() {
        if (registered) {
            burnedTokensPerSecond.update(1);
        }
    }

    protected static void addTransferTokenOperation() {
        if (registered) {
            transferredTokensPerSecond.update(1);
        }
    }

    protected static void addMintTokenOperation() {
        if (registered) {
            mintedTokensPerSecond.update(1);
        }
    }

    /**
     * Registers the {@link NftLedger} statistics with the specified {@link Platform} instance.
     *
     * @param platform
     * 		the platform instance
     */
    public static void register(final Platform platform) {
        mintTokenMicroSec = platform.getContext().getMetrics().getOrCreate(MINT_TOKEN_MICRO_SEC_CONFIG);
        transferTokenMicroSec = platform.getContext().getMetrics().getOrCreate(TRANSFER_TOKEN_MICRO_SEC_CONFIG);
        burnTokenMicroSec = platform.getContext().getMetrics().getOrCreate(BURN_TOKEN_MICRO_SEC_CONFIG);
        mintedTokensPerSecond = platform.getContext().getMetrics().getOrCreate(MINTED_TOKENS_PER_SECOND_CONFIG);
        transferredTokensPerSecond =
                platform.getContext().getMetrics().getOrCreate(TRANSFERRED_TOKENS_PER_SECOND_CONFIG);
        burnedTokensPerSecond = platform.getContext().getMetrics().getOrCreate(BURNED_TOKENS_PER_SECOND_CONFIG);

        registered = true;
    }

    /**
     * Intended for unit testing purposes only
     */
    public static void unregister() {
        registered = false;
    }
}
