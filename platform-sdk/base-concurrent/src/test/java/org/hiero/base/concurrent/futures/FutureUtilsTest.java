// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FutureUtilsTest {

    @Test
    void awaitAllResolvesCompletedFutures() throws ExecutionException, InterruptedException {
        final Map<String, Future<Integer>> futures = Map.of(
                "a", CompletableFuture.completedFuture(1),
                "b", CompletableFuture.completedFuture(2),
                "c", CompletableFuture.completedFuture(3));

        final Map<String, Integer> result = FutureUtils.awaitAll(futures);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1, "b", 2, "c", 3));
    }

    @Test
    void awaitAllReturnsEmptyMapForEmptyInput() throws ExecutionException, InterruptedException {
        final Map<Integer, Future<String>> futures = Map.of();

        final Map<Integer, String> result = FutureUtils.awaitAll(futures);

        assertThat(result).isEmpty();
    }

    @Test
    void awaitAllPropagatesExecutionException() {
        final RuntimeException cause = new RuntimeException("generation failed");
        final CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(cause);

        final Map<String, Future<String>> futures =
                Map.of("ok", CompletableFuture.completedFuture("value"), "fail", failed);

        assertThatThrownBy(() -> FutureUtils.awaitAll(futures))
                .isInstanceOf(ExecutionException.class)
                .hasCause(cause);
    }

    @Test
    void awaitAllPropagatesInterruptedException() {
        final CompletableFuture<String> neverCompletes = new CompletableFuture<>();

        final Map<String, Future<String>> futures = Map.of("pending", neverCompletes);

        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> FutureUtils.awaitAll(futures))
                .isInstanceOf(Exception.class)
                .satisfies(e -> assertThat(e).isInstanceOfAny(
                        InterruptedException.class, java.util.concurrent.CancellationException.class));

        // Clear interrupt flag if still set
        final boolean ignored = Thread.interrupted();
    }
}
