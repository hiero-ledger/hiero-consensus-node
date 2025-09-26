// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import static org.hiero.otter.fixtures.internal.helpers.Utils.collectMarkers;
import static org.hiero.otter.fixtures.result.SubscriberAction.CONTINUE;
import static org.hiero.otter.fixtures.result.SubscriberAction.UNSUBSCRIBE;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;

/**
 * Continuous assertions for {@link MultipleNodeLogResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeLogResultsContinuousAssert
        extends AbstractMultipleNodeContinuousAssertion<
                MultipleNodeLogResultsContinuousAssert, MultipleNodeLogResults> {

    private final Set<Marker> suppressedLogMarkers = ConcurrentHashMap.newKeySet();

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the actual {@link MultipleNodeLogResults} to assert
     */
    public MultipleNodeLogResultsContinuousAssert(@Nullable final MultipleNodeLogResults actual) {
        super(actual, MultipleNodeLogResultsContinuousAssert.class);
    }

    /**
     * Creates a continuous assertion for the given {@link MultipleNodeLogResults}.
     *
     * @param actual the {@link MultipleNodeLogResults} to assert
     * @return a continuous assertion for the given {@link MultipleNodeLogResults}
     */
    @NonNull
    public static MultipleNodeLogResultsContinuousAssert assertContinuouslyThat(
            @Nullable final MultipleNodeLogResults actual) {
        return new MultipleNodeLogResultsContinuousAssert(actual);
    }

    /**
     * Suppresses the given {@link LogMarker} from the assertions.
     *
     * @param logMarker the {@link LogMarker} to suppress
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsContinuousAssert startSuppressingLogMarker(@NonNull final LogMarker logMarker) {
        suppressedLogMarkers.add(logMarker.getMarker());
        return this;
    }

    /**
     * Stops suppressing the given {@link LogMarker} from the assertions.
     *
     * @param logMarker the {@link LogMarker}
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsContinuousAssert stopSuppressingLogMarker(@NonNull final LogMarker logMarker) {
        suppressedLogMarkers.remove(logMarker.getMarker());
        return this;
    }

    /**
     * Verifies that no log messages with the specified markers are logged.
     *
     * @param first the first marker to check
     * @param rest additional markers to check
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsContinuousAssert haveNoMessageWithMarkers(
            @NonNull final LogMarker first, @Nullable final LogMarker... rest) {
        final Set<Marker> markers = collectMarkers(first, rest);
        return checkContinuously(logEntry -> {
            if (markers.contains(logEntry.marker())) {
                failWithMessage(
                        "Expected no message with any of %s markers, but found %s in %n%s",
                        markers, logEntry.marker(), logEntry);
            }
        });
    }

    /**
     * Verifies that no log messages with a level higher than the specified level exist.
     *
     * @param level the maximum log level to allow
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsContinuousAssert haveNoMessageWithLevelHigherThan(@NonNull final Level level) {
        return checkContinuously(logEntry -> {
            if (logEntry.level().intLevel() < level.intLevel()) {
                failWithMessage(
                        "Expected no message with level higher than %s, but found %s in %n%s",
                        level, logEntry.level(), logEntry);
            }
        });
    }

    /**
     * Verifies that no log messages with an error log level exist.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public MultipleNodeLogResultsContinuousAssert haveNoErrorLevelMessages() {
        return haveNoMessageWithLevelHigherThan(Level.WARN);
    }

    private MultipleNodeLogResultsContinuousAssert checkContinuously(final Consumer<StructuredLog> check) {
        isNotNull();

        final LogSubscriber subscriber = logEntry -> switch (state) {
            case ACTIVE -> {
                if ((logEntry.nodeId() == null || !suppressedNodeIds.contains(logEntry.nodeId()))
                        && (logEntry.marker() == null || !suppressedLogMarkers.contains(logEntry.marker()))) {
                    check.accept(logEntry);
                }
                yield CONTINUE;
            }
            case PAUSED -> CONTINUE;
            case DESTROYED -> UNSUBSCRIBE;
        };

        actual.subscribe(subscriber);

        return this;
    }
}
