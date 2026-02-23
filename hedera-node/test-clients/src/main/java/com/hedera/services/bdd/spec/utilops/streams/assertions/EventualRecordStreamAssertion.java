// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

/**
 * A {@link com.hedera.services.bdd.spec.utilops.UtilOp} that registers itself with {@link
 * StreamFileAccess} and continually updates the {@link RecordStreamAssertion} yielded by a given
 * factory with each new {@link RecordStreamItem}.
 */
public class EventualRecordStreamAssertion extends AbstractEventualStreamAssertion {
    /**
     * The factory for the assertion to be tested.
     */
    private final Function<HapiSpec, RecordStreamAssertion> assertionFactory;

    private final boolean replayExistingFiles;

    private boolean stopAfterFirstSuccess = false;

    /**
     * Once this op is submitted, the assertion to be tested.
     */
    @Nullable
    private RecordStreamAssertion assertion;

    private boolean needsBackgroundTraffic = false;

    /**
     * Returns an {@link EventualRecordStreamAssertion} that will pass as long as the given assertion does not
     * throw an {@link AssertionError} before its timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual record stream assertion that must not fail
     */
    public static EventualRecordStreamAssertion eventuallyAssertingNoFailures(
            final Function<HapiSpec, RecordStreamAssertion> assertionFactory) {
        return new EventualRecordStreamAssertion(assertionFactory, true, false).withBackgroundTraffic();
    }

    /**
     * Returns an {@link EventualRecordStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the default timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual record stream assertion that must pass
     */
    public static EventualRecordStreamAssertion eventuallyAssertingExplicitPass(
            final Function<HapiSpec, RecordStreamAssertion> assertionFactory) {
        return new EventualRecordStreamAssertion(assertionFactory, false, false);
    }

    /**
     * Returns an {@link EventualRecordStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the default timeout after receiving a replay of any existing files.
     * @param assertionFactory the assertion factory
     * @return the eventual record stream assertion that must pass
     */
    public static EventualRecordStreamAssertion eventuallyAssertingExplicitPassWithReplay(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertionFactory,
            @NonNull final Duration timeout) {
        requireNonNull(assertionFactory);
        return new EventualRecordStreamAssertion(assertionFactory, false, timeout, true).withBackgroundTraffic();
    }

    @Override
    public boolean needsBackgroundTraffic() {
        return needsBackgroundTraffic;
    }

    /**
     * Returns an {@link EventualRecordStreamAssertion} enabling background traffic.
     * @return the eventual record stream assertion with background traffic
     */
    public EventualRecordStreamAssertion withBackgroundTraffic() {
        this.needsBackgroundTraffic = true;
        return this;
    }

    /**
     * Returns an {@link EventualRecordStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the default timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual record stream assertion that must pass
     */
    public static EventualRecordStreamAssertion eventuallyAssertingExplicitPass(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertionFactory,
            @NonNull final Duration timeout) {
        requireNonNull(assertionFactory);
        requireNonNull(timeout);
        return new EventualRecordStreamAssertion(assertionFactory, false, timeout, false);
    }

    private EventualRecordStreamAssertion(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            final boolean replayExistingFiles) {
        super(hasPassedIfNothingFailed);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.replayExistingFiles = replayExistingFiles;
    }

    private EventualRecordStreamAssertion(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            @NonNull final Duration timeout,
            final boolean replayExistingFiles) {
        super(hasPassedIfNothingFailed, timeout);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.replayExistingFiles = replayExistingFiles;
    }

    @Override
    protected boolean submitOp(final HapiSpec spec) throws Throwable {
        assertion = requireNonNull(assertionFactory.apply(spec));
        unsubscribe = STREAM_FILE_ACCESS.subscribe(recordStreamLocFor(spec), new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return replayExistingFiles;
            }

            @Override
            public void onNewItem(@NonNull final RecordStreamItem item) {
                requireNonNull(item);
                if (assertion.isApplicableTo(item)) {
                    try {
                        if (assertion.test(item)) {
                            result.pass();
                            if (stopAfterFirstSuccess) {
                                unsubscribe.run();
                            }
                        }
                    } catch (final AssertionError e) {
                        result.fail(e.getMessage());
                    }
                }
            }

            @Override
            public void onNewSidecar(TransactionSidecarRecord sidecar) {
                if (assertion.isApplicableToSidecar(sidecar)) {
                    try {
                        if (assertion.testSidecar(sidecar)) {
                            result.pass();
                        }
                    } catch (final AssertionError e) {
                        result.fail(e.getMessage());
                    }
                }
            }

            @Override
            public String name() {
                return assertion.toString();
            }
        });
        return false;
    }

    @Override
    protected String assertionDescription() {
        return assertion == null ? "<N/A>" : assertion.toString();
    }

    @Override
    public String toString() {
        return "EventuallyRecordStream{" + assertionDescription() + "}";
    }

    /**
     * Returns the record stream location for the first listed node in the network targeted
     * by the given spec.
     *
     * @param spec the spec
     * @return a record stream location for the first listed node in the network
     */
    private static Path recordStreamLocFor(@NonNull final HapiSpec spec) {
        return spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(RECORD_STREAMS_DIR);
    }

    /**
     * Configures this assertion to automatically unsubscribe from the record stream
     * once a passing validation occurs. When enabled, the listener will stop receiving
     * new record stream items immediately after the first successful validation,
     * conserving system resources by preventing unnecessary processing.
     *
     * @return this instance for method chaining
     */
    public EventualRecordStreamAssertion stopAfterFirstSuccess() {
        this.stopAfterFirstSuccess = true;
        return this;
    }
}
