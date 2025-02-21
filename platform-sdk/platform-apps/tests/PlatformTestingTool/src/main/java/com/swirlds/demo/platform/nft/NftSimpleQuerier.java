// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_9_6;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.common.utility.StopWatch;
import com.swirlds.demo.platform.PlatformTestingToolState;
import com.swirlds.demo.platform.nft.config.NftQueryConfig;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.platform.system.Platform;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class performs simple queries against the {@link NftLedger}.
 */
public class NftSimpleQuerier {

    private static final Random RANDOM = new SecureRandom();

    private static final Logger logger = LogManager.getLogger(NftSimpleQuerier.class);

    private static final RunningAverageMetric.Config TOKENS_PER_KEY_CONFIG = new RunningAverageMetric.Config(
                    "NFT", "nftTokensPerKey")
            .withDescription("avg number of tokens each key has when queried")
            .withFormat(FORMAT_9_6)
            .withHalfLife(10.0);
    private static RunningAverageMetric tokensPerKey;

    private static final RunningAverageMetric.Config NFT_QUERIES_ANSWERED_MICRO_SEC_CONFIG =
            new RunningAverageMetric.Config("NFT", "nftQueriesAnsweredMicroSec")
                    .withDescription("avg time taken to answer a query through NftLedger (in microseconds)")
                    .withFormat(FORMAT_9_6)
                    .withHalfLife(10.0);
    private static RunningAverageMetric nftQueriesAnsweredMicroSec;

    private static int PAGE_SIZE = 5;

    protected static void registerStats(final Platform platform) {
        tokensPerKey = platform.getContext().getMetrics().getOrCreate(TOKENS_PER_KEY_CONFIG);
        nftQueriesAnsweredMicroSec =
                platform.getContext().getMetrics().getOrCreate(NFT_QUERIES_ANSWERED_MICRO_SEC_CONFIG);
    }

    protected static void setConfig(final NftQueryConfig config) {
        if (config.getPageSize() > 0) {
            PAGE_SIZE = config.getPageSize();
        }
    }

    /**
     * Perform a single query.
     *
     * @param state
     * 		the state to query
     * @param queriesAnswered
     * 		record stats for the query here
     */
    public static void execute(final PlatformTestingToolState state, final SpeedometerMetric queriesAnswered) {
        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        final NftLedger ledger = state.getNftLedger();
        final Optional<NftId> nftIdCheck = state.getStateExpectedMap().getAnyNftId();

        if (nftIdCheck.isEmpty()) {
            return;
        }

        final NftId nftId = nftIdCheck.get();
        final Nft nft = ledger.getTokenIdToToken().get(nftId);
        if (nft == null) {
            return;
        }

        final MapKey key = nft.getMapKey();

        final int totalTokens = ledger.numberOfTokensByAccount(key);
        if (totalTokens < 1) {
            return;
        }

        final int limitToQuery;
        if (totalTokens - PAGE_SIZE < 1) {
            limitToQuery = totalTokens;
        } else {
            limitToQuery = totalTokens - PAGE_SIZE;
        }

        final int startIndex = RANDOM.nextInt(limitToQuery);

        final int limit = Math.min(startIndex + PAGE_SIZE, totalTokens);
        ledger.getTokensByAccount(key, startIndex, limit);

        final ReferenceNftLedger referenceNftLedger = state.getReferenceNftLedger();
        if (referenceNftLedger.isTokenTracked(nftId)) {

            final Nft referenceToken = referenceNftLedger.getTokenMap().get(nftId);

            if (!Objects.equals(referenceToken, nft)) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "reference token " + referenceToken + " does not match token in state " + nft);
            }
        }

        stopWatch.stop();
        nftQueriesAnsweredMicroSec.update(stopWatch.getTime(TimeUnit.MICROSECONDS));
        tokensPerKey.update(totalTokens);
        queriesAnswered.update(1);
    }
}
