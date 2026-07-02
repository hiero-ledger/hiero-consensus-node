// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.types.StreamMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
import com.hedera.services.bdd.junit.support.BlockSourceFactory;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Function;

/**
 * A stream assertion that dynamically routes to either {@link EventualRecordStreamAssertion} or
 * {@link EventualBlockStreamAssertion} based on the active {@code blockStream.streamMode} and
 * the concrete type of the {@link StreamAssertion} produced by the factory.
 *
 * <ul>
 *   <li>{@link BlockStreamAssertion} — always routes to {@link EventualBlockStreamAssertion}
 *       (blocks are produced in both BOTH and BLOCKS modes).</li>
 *   <li>{@link RecordStreamAssertion} — in RECORDS/BOTH mode routes to
 *       {@link EventualRecordStreamAssertion}; in BLOCKS mode adapts via
 *       {@link RecordStreamToBlockAssertionAdapter} and routes to the block stream path.</li>
 * </ul>
 */
public class EventualStreamAssertion extends AbstractEventualStreamAssertion {
    private static final Duration BLOCK_NODE_READ_TIMEOUT_FLOOR = Duration.ofSeconds(45);

    private final Function<HapiSpec, ? extends StreamAssertion> assertionFactory;
    private final boolean hasPassedIfNothingFailed;
    private final boolean needsBackgroundTraffic;
    private final boolean replayExistingFiles;
    private boolean stopAfterFirstSuccess = false;

    @Nullable
    private final Duration timeout;

    @Nullable
    private AbstractEventualStreamAssertion delegate;

    private EventualStreamAssertion(
            @NonNull final Function<HapiSpec, ? extends StreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            @Nullable final Duration timeout,
            final boolean needsBackgroundTraffic,
            final boolean replayExistingFiles) {
        super(hasPassedIfNothingFailed);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.hasPassedIfNothingFailed = hasPassedIfNothingFailed;
        this.timeout = timeout;
        this.needsBackgroundTraffic = needsBackgroundTraffic;
        this.replayExistingFiles = replayExistingFiles;
    }

    public static EventualStreamAssertion streamMustIncludeNoFailures(
            @NonNull final Function<HapiSpec, ? extends StreamAssertion> assertion,
            final boolean needsBackgroundTraffic) {
        return new EventualStreamAssertion(assertion, true, null, needsBackgroundTraffic, false);
    }

    public static EventualStreamAssertion streamMustIncludePass(
            @NonNull final Function<HapiSpec, ? extends StreamAssertion> assertion,
            @Nullable final Duration timeout,
            final boolean needsBackgroundTraffic) {
        return new EventualStreamAssertion(assertion, false, timeout, needsBackgroundTraffic, false);
    }

    public static EventualStreamAssertion streamMustIncludePassWithReplay(
            @NonNull final Function<HapiSpec, ? extends StreamAssertion> assertion, @NonNull final Duration timeout) {
        return new EventualStreamAssertion(assertion, false, timeout, true, true);
    }

    @Override
    public boolean needsBackgroundTraffic() {
        return delegate != null ? delegate.needsBackgroundTraffic() : needsBackgroundTraffic;
    }

    @Override
    public void assertHasPassed() {
        if (delegate != null) {
            delegate.assertHasPassed();
        } else {
            super.assertHasPassed();
        }
    }

    public EventualStreamAssertion stopAfterFirstSuccess() {
        this.stopAfterFirstSuccess = true;
        return this;
    }

    @Override
    public void unsubscribe() {
        if (delegate != null) {
            delegate.unsubscribe();
        }
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        final var assertion = assertionFactory.apply(spec);
        if (assertion instanceof BlockStreamAssertion) {
            @SuppressWarnings("unchecked")
            final var blockFactory = (Function<HapiSpec, BlockStreamAssertion>) (Function<?, ?>) assertionFactory;
            delegate = createBlockDelegate(blockFactory);
        } else if (assertion instanceof RecordStreamAssertion) {
            @SuppressWarnings("unchecked")
            final var recordFactory = (Function<HapiSpec, RecordStreamAssertion>) (Function<?, ?>) assertionFactory;
            final var streamMode = resolveStreamMode(spec);
            if (streamMode == BLOCKS) {
                final long shard = spec.setup().shard();
                final long realm = spec.setup().realm();
                final Function<HapiSpec, BlockStreamAssertion> adaptedFactory =
                        s -> new RecordStreamToBlockAssertionAdapter(
                                recordFactory.apply(s), shard, realm, hasPassedIfNothingFailed);
                delegate = createBlockDelegate(adaptedFactory);
            } else {
                final EventualRecordStreamAssertion recordAssertion;
                if (hasPassedIfNothingFailed) {
                    recordAssertion = EventualRecordStreamAssertion.eventuallyAssertingNoFailures(recordFactory);
                } else if (timeout != null && replayExistingFiles) {
                    // Replay already-written record files so a genesis-time externalization (e.g. a
                    // LedgerIdPublication) that landed in the still-open genesis record file before this
                    // assertion subscribed is still seen.
                    recordAssertion = EventualRecordStreamAssertion.eventuallyAssertingExplicitPassWithReplay(
                            recordFactory, timeout);
                } else if (timeout != null) {
                    recordAssertion =
                            EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(recordFactory, timeout);
                } else {
                    recordAssertion = EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(recordFactory);
                }
                if (needsBackgroundTraffic) {
                    recordAssertion.withBackgroundTraffic();
                }
                if (stopAfterFirstSuccess) {
                    recordAssertion.stopAfterFirstSuccess();
                }
                delegate = recordAssertion;
            }
        } else {
            throw new IllegalArgumentException("Unknown assertion type: " + assertion.getClass());
        }
        delegate.execFor(spec);
        return false;
    }

    @Override
    protected String assertionDescription() {
        return delegate != null ? delegate.assertionDescription() : "<pending stream mode detection>";
    }

    private EventualBlockStreamAssertion createBlockDelegate(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> factory) {
        final var effectiveTimeout = effectiveBlockTimeout();
        if (replayExistingFiles && effectiveTimeout != null) {
            return EventualBlockStreamAssertion.eventuallyAssertingExplicitPassWithReplay(factory, effectiveTimeout);
        }
        if (hasPassedIfNothingFailed) {
            return EventualBlockStreamAssertion.eventuallyAssertingNoFailures(factory);
        }
        if (effectiveTimeout != null) {
            return EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(factory, effectiveTimeout);
        }
        return EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(factory);
    }

    /**
     * Returns the timeout to use for block-stream delegates. When blocks are served through a
     * block node (no local block files to read), the subscription path can lag several seconds
     * behind block production under CI load; explicit-pass budgets tuned for local file reads
     * then time out spuriously. Floor them at {@link #BLOCK_NODE_READ_TIMEOUT_FLOOR}. No-failures
     * assertions are left untouched since they pass, not fail, on timeout.
     */
    @Nullable
    private Duration effectiveBlockTimeout() {
        if (hasPassedIfNothingFailed || BlockNodeReader.activeNetwork() == null) {
            return timeout;
        }
        return (timeout == null || timeout.compareTo(BLOCK_NODE_READ_TIMEOUT_FLOOR) < 0)
                ? BLOCK_NODE_READ_TIMEOUT_FLOOR
                : timeout;
    }

    private static StreamMode resolveStreamMode(@NonNull final HapiSpec spec) {
        try {
            return BlockSourceFactory.effectiveStartupProperties(spec).getStreamMode("blockStream.streamMode");
        } catch (final Exception e) {
            return StreamMode.BOTH;
        }
    }
}
