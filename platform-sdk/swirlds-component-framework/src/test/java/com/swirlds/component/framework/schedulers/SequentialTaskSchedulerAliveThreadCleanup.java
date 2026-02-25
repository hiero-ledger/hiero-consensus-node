// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.component.framework.schedulers.internal.SequentialThreadTaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * {@link com.swirlds.component.framework.schedulers.internal.SequentialThreadTaskScheduler}
 *  has the potential issue that if the {@link com.swirlds.component.framework.model.WiringModel} is not closed after the test finishes, it can leak the blocked thread up to when the JVM is shutdown.
 * To prevent this situation, this cleanup mechanism searches for blocked threads, attempts to finish them gracefully, and reports back if it fails, also failing the test.
 *
 * NOTE: It also detects problems outside the threads created in the test (if any other test had the issue, and the sequentialTaskSchedulerTest executed after it).
 *  That's why all threads are printed with the stacktrace.
 */
@TestInstance(Lifecycle.PER_METHOD)
public interface SequentialTaskSchedulerAliveThreadCleanup {

    /**
     *  This is a "best effort" attempt to not leave any thread alive before finishing the test.
     *  ONLY applies to SEQUENTIAL_THREAD.
     */
    @AfterEach
    default void searchThreadsTryStopOrFail() throws InterruptedException {

        final int retries = 3;
        Collection<Thread> liveThreads = List.of();
        for (int i = 0; i < retries; i++) {
            liveThreads = getLivePlatformThreadByNameMatching(
                    name -> name.startsWith(SequentialThreadTaskScheduler.THREAD_NAME_PREFIX)
                            && name.endsWith(SequentialThreadTaskScheduler.THREAD_NAME_SUFFIX));
            if (liveThreads.isEmpty()) {
                break;
            } else {
                System.out.println(
                        "Some scheduler threads are still alive, waiting for them to finish normally. Try:" + (i + 1));
                sleep((int) Math.pow(10, (i + 1)));
            }
        }

        if (!liveThreads.isEmpty()) {
            // There is an issue preventing the thread to normally finish.
            final StringWriter sw = new StringWriter();
            sw.append(("Some scheduler threads are still alive after %d retries and they should not. ")
                    .formatted(retries));
            liveThreads.forEach(t -> {
                StringBuilder exception = new StringBuilder("\n");
                sw.append("+".repeat(40));
                sw.append("\n");
                sw.append(t.getName());
                sw.append("\n");
                for (StackTraceElement s : t.getStackTrace()) {
                    exception.append(s).append("\n\t\t");
                }
                sw.append(exception);
                sw.append("\n\n");
                //
                t.interrupt();
            });
            // mark the test as fail to analyze
            fail(sw.toString());
        }
    }

    /**
     * Search for all alive platform threads which name's matches a given predicate.
     * @param predicate that the name of the platform thread needs to match to be returned
     * @return the list of threads that matched the predicate.
     */
    @NonNull
    private static Collection<Thread> getLivePlatformThreadByNameMatching(@NonNull final Predicate<String> predicate) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> predicate.test(t.getName()))
                .toList();
    }
}
