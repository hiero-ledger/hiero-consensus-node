// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.state;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.statevalidation.parameterresolver.HashInfo;
import com.hedera.statevalidation.parameterresolver.HashInfoResolver;
import com.hedera.statevalidation.parameterresolver.InitUtils;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.reconnect.ConcurrentBlockingIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class, HashInfoResolver.class})
@Tag("rehash")
public class Rehash {

    private static final Logger logger = LogManager.getLogger(Rehash.class);

    /**
     * This parameter defines how deep the hash tree should be traversed.
     * Note that it doesn't go below the top level of VirtualMap even if the depth is set to a higher value.
     */
    public static final int HASH_DEPTH = 5;

    @Test
    void reHash(DeserializedSignedState deserializedSignedState, Report report) {
        final Hash originalHash = deserializedSignedState.originalHash();

        final VirtualMap vm = (VirtualMap)
                deserializedSignedState.reservedSignedState().get().getState().getRoot();
        final Hash calculatedHash = rehashVm(vm);
        // Add data to the report, adding it before the assertion so that the report is written even if the test fails
        var stateReport = report.getStateReport();
        stateReport.setRootHash(originalHash.toString());
        stateReport.setCalculatedHash(calculatedHash.toString());
        report.setRoundNumber(
                deserializedSignedState.reservedSignedState().get().getRound());

        assertEquals(originalHash, calculatedHash);
    }

    /**
     * This test validates the Merkle tree structure of the state.
     *
     * @param deserializedSignedState The deserialized signed state, propagated by the StateResolver.
     * @param report                  The report object, propagated by the ReportResolver.
     * @param hashInfo                The hash info object, propagated by the HashInfoResolver.
     */
    @Test
    void validateMerkleTree(DeserializedSignedState deserializedSignedState, Report report, HashInfo hashInfo) {

        var platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        var infoStringFromState = platformStateFacade.getInfoString(
                deserializedSignedState.reservedSignedState().get().getState(), HASH_DEPTH);

        final var originalLines = Arrays.asList(hashInfo.content().split("\n")).getFirst();
        final var fullList = Arrays.asList(infoStringFromState.split("\n"));
        // skipping irrelevant lines, capturing only the one with the root hash
        final var revisedLines = filterLines(fullList);

        assertEquals(originalLines, revisedLines, "The Merkle tree structure does not match the expected state.");
    }

    private String filterLines(List<String> lines) {
        for (String line : lines) {
            if (line.contains("(root)")) {
                return line;
            }
        }
        return "root hash not found";
    }

    @SuppressWarnings("rawtypes")
    public Hash rehashVm(@NonNull final VirtualMap virtualMap) {
        final int MAX_FULL_REHASHING_TIMEOUT = 3600; // 1 hour
        final int MAX_REHASHING_BUFFER_SIZE = 10_000_000; // copied from VirtualMap class
        final VirtualMapConfig virtualMapConfig = InitUtils.getConfiguration().getConfigData(VirtualMapConfig.class);
        final VirtualDataSource dataSource = virtualMap.getDataSource();
        final RecordAccessor records = virtualMap.getRecords();
        requireNonNull(records, "Records must be initialized before rehashing");

        final ConcurrentBlockingIterator<VirtualLeafBytes> rehashIterator =
                new ConcurrentBlockingIterator<>(MAX_REHASHING_BUFFER_SIZE);
        final CompletableFuture<Void> leafFeedFuture = new CompletableFuture<>();
        // getting a range that is relevant for the virtual map
        final long firstLeafPath = virtualMap.getMetadata().getFirstLeafPath();
        final long lastLeafPath = virtualMap.getMetadata().getLastLeafPath();
        if (firstLeafPath < 0 || lastLeafPath < 0) {
            throw new IllegalStateException("Paths range is invalid");
        }

        logger.info("Doing full rehash for the path range: {} - {}  in the VirtualMap", firstLeafPath, lastLeafPath);
        final VirtualHasher hasher = new VirtualHasher();

        // This background thread will be responsible for feeding the iterator with data.
        new ThreadConfiguration(getStaticThreadManager())
                .setComponent("virtualmap")
                .setThreadName("leafFeeder")
                .setRunnable(() -> {
                    final long onePercent = (lastLeafPath - firstLeafPath) / 100 + 1;
                    try {
                        for (long i = firstLeafPath; i <= lastLeafPath; i++) {
                            try {
                                final VirtualLeafBytes leafBytes = dataSource.loadLeafRecord(i);
                                assert leafBytes != null : "Leaf bytes should not be null";
                                try {
                                    rehashIterator.supply(leafBytes);
                                } catch (final MerkleSynchronizationException e) {
                                    throw e;
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new MerkleSynchronizationException(
                                            "Interrupted while waiting to supply a new leaf to the hashing iterator buffer",
                                            e);
                                } catch (final Exception e) {
                                    throw new MerkleSynchronizationException(
                                            "Failed to handle a leaf during full rehashing", e);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            // we don't care about tracking progress on small maps.
                            if (onePercent > 10 && i % onePercent == 0) {
                                logger.debug(
                                        "Full rehash progress for the VirtualMap: {}%",
                                        (i - firstLeafPath) / onePercent + 1);
                            }
                        }
                    } finally {
                        rehashIterator.close();
                    }
                    leafFeedFuture.complete(null);
                })
                .setExceptionHandler((thread, exception) -> {
                    // Shut down the iterator.
                    rehashIterator.close();
                    final var message = "VirtualMap failed to feed all leaves the hasher";
                    logger.error(message, exception);
                    leafFeedFuture.completeExceptionally(new MerkleSynchronizationException(message, exception));
                })
                .build()
                .start();
        try {
            final long start = System.currentTimeMillis();
            leafFeedFuture.get(MAX_FULL_REHASHING_TIMEOUT, SECONDS);
            final long secondsSpent = (System.currentTimeMillis() - start) / 1000;
            logger.info("It took {} seconds to feed all leaves to the hasher for the VirtualMap", secondsSpent);
            return hasher.hash(records::findHash, rehashIterator, firstLeafPath, lastLeafPath, null, virtualMapConfig);
        } catch (ExecutionException e) {
            final var message = "VirtualMap failed to get hash during full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            final var message = "VirtualMap interrupted while full rehashing";
            throw new MerkleSynchronizationException(message, e);
        } catch (TimeoutException e) {
            final var message = "VirtualMap wasn't able to finish full rehashing in time";
            throw new MerkleSynchronizationException(message, e);
        }
    }
}
