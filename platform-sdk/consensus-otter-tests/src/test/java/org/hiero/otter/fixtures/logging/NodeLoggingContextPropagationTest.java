// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging;

import static com.swirlds.logging.legacy.LogMarker.DEMO_INFO;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.context.ContextAwareThreadFactory;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.hiero.otter.fixtures.turtle.logging.TurtleLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NodeLoggingContextPropagationTest {

    private static final Logger LOGGER = LogManager.getLogger();

    @BeforeEach
    void resetSubscribers() {
        InMemorySubscriptionManager.INSTANCE.reset();
    }

    @AfterEach
    void cleanupSubscribers() {
        InMemorySubscriptionManager.INSTANCE.reset();
    }

    @Test
    void propagatesNodeIdAcrossAsyncBoundaries(@TempDir @NonNull final Path tempDir) throws Exception {
        final TurtleLogging logging = new TurtleLogging(tempDir);

        final NodeId nodeA = NodeId.of(1L);
        final NodeId nodeB = NodeId.of(2L);

        logging.addNodeLogging(nodeA, tempDir.resolve("node-1"));
        logging.addNodeLogging(nodeB, tempDir.resolve("node-2"));

        LOGGER.info(DEMO_INFO.getMarker(), "FALLBACK-LOG");

        final Map<String, List<StructuredLog>> logsByNode = new ConcurrentHashMap<>();
        InMemorySubscriptionManager.INSTANCE.subscribe(log -> {
            final String message = log.message();
            if (message.startsWith("NODE-")) {
                final String key = log.nodeId() == null
                        ? "unknown"
                        : Long.toString(log.nodeId().id());
                logsByNode
                        .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                        .add(log);
            }
            return SubscriberAction.CONTINUE;
        });

        emitLogsForNode(nodeA, "NODE-1");
        emitLogsForNode(nodeB, "NODE-2");

        final Path nodeALog = tempDir.resolve("node-1/output/swirlds.log");
        final Path nodeBLog = tempDir.resolve("node-2/output/swirlds.log");
        awaitFile(nodeALog, Duration.ofSeconds(2));
        awaitFile(nodeBLog, Duration.ofSeconds(2));

        final String nodeALogContent = Files.readString(nodeALog);
        final String nodeBLogContent = Files.readString(nodeBLog);
        assertThat(nodeALogContent).contains("NODE-1|main-thread").contains("NODE-1|executor");
        assertThat(nodeBLogContent).contains("NODE-2|scheduled").contains("NODE-2|cfExecutor");

        final List<StructuredLog> nodeALogs = logsByNode.getOrDefault("1", List.of());
        final List<StructuredLog> nodeBLogs = logsByNode.getOrDefault("2", List.of());
        final List<String> nodeAMessages =
                nodeALogs.stream().map(StructuredLog::message).toList();
        final List<String> nodeBMessages =
                nodeBLogs.stream().map(StructuredLog::message).toList();

        assertThat(nodeALogs).hasSizeGreaterThanOrEqualTo(5);
        assertThat(nodeBLogs).hasSizeGreaterThanOrEqualTo(5);

        assertThat(nodeALogs).allSatisfy(log -> {
            assertThat(log.nodeId()).isNotNull();
            assertThat(log.nodeId().id()).isEqualTo(1L);
        });
        assertThat(nodeBLogs).allSatisfy(log -> {
            assertThat(log.nodeId()).isNotNull();
            assertThat(log.nodeId().id()).isEqualTo(2L);
        });

        assertThat(nodeALogs).anySatisfy(log -> {
            assertThat(log.marker()).isNotNull();
            assertThat(log.marker().getName()).isEqualTo(STARTUP.getMarker().getName());
        });
        assertThat(nodeBLogs).anySatisfy(log -> {
            assertThat(log.marker()).isNotNull();
            assertThat(log.marker().getName()).isEqualTo(STARTUP.getMarker().getName());
        });

        assertThat(nodeAMessages)
                .contains("NODE-1|main-thread", "NODE-1|executor", "NODE-1|scheduled", "NODE-1|cfExecutor");
        assertThat(nodeBMessages)
                .contains("NODE-2|main-thread", "NODE-2|executor", "NODE-2|scheduled", "NODE-2|cfExecutor");

        assertThat(logsByNode).doesNotContainKey("unknown");
    }

    private void emitLogsForNode(@NonNull final NodeId nodeId, @NonNull final String prefix) throws Exception {
        final String contextValue = Long.toString(nodeId.id());
        final Marker infoMarker = DEMO_INFO.getMarker();
        try (var scope = NodeLoggingContext.install(contextValue)) {
            LOGGER.info(infoMarker, "{}|main-thread", prefix);
            LOGGER.info(STARTUP.getMarker(), "{}|startup-marker", prefix);

            final ScheduledExecutorService scheduler = NodeLoggingContext.wrap(
                    Executors.newSingleThreadScheduledExecutor(new ContextAwareThreadFactory()));
            final ExecutorService executor =
                    NodeLoggingContext.wrap(Executors.newFixedThreadPool(2, new ContextAwareThreadFactory()));

            try {
                executor.submit(() -> {
                            LOGGER.info(infoMarker, "{}|executor", prefix);
                        })
                        .get(5, TimeUnit.SECONDS);

                final CountDownLatch scheduledLatch = new CountDownLatch(1);
                final ScheduledFuture<?> scheduledFuture = scheduler.schedule(
                        () -> {
                            LOGGER.info(infoMarker, "{}|scheduled", prefix);
                            scheduledLatch.countDown();
                        },
                        25,
                        TimeUnit.MILLISECONDS);
                scheduledFuture.get(5, TimeUnit.SECONDS);
                scheduledLatch.await(5, TimeUnit.SECONDS);

                final Callable<String> commonPoolCallable = NodeLoggingContext.wrap(() -> {
                    LOGGER.info(infoMarker, "{}|commonPool", prefix);
                    return "ok";
                });
                final CompletableFuture<String> commonPoolFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return commonPoolCallable.call();
                    } catch (final Exception e) {
                        throw new IllegalStateException("Callable should not throw", e);
                    }
                });
                assertThat(commonPoolFuture.get(5, TimeUnit.SECONDS)).isEqualTo("ok");

                CompletableFuture.runAsync(
                                () -> {
                                    LOGGER.info(infoMarker, "{}|cfExecutor", prefix);
                                },
                                executor)
                        .get(5, TimeUnit.SECONDS);
            } finally {
                shutdownExecutor(executor);
                shutdownExecutor(scheduler);
            }
        }
    }

    private static void shutdownExecutor(@NonNull final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Executor did not terminate in time");
        }
    }

    private static void shutdownExecutor(@NonNull final ScheduledExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Scheduler did not terminate in time");
        }
    }

    private static void awaitFile(@NonNull final Path file, @NonNull final Duration timeout)
            throws InterruptedException, IOException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.size(file) > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for log file " + file);
    }
}
