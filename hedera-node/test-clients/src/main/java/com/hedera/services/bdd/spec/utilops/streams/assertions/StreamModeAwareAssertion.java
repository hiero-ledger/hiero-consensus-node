// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.types.StreamMode;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Function;

/**
 * A stream assertion that dynamically routes to either {@link EventualRecordStreamAssertion} or
 * {@link EventualBlockStreamAssertion} based on the active {@code blockStream.streamMode}.
 *
 * <p>In RECORDS or BOTH mode, delegates to the record stream path.
 * In BLOCKS mode, wraps the {@link RecordStreamAssertion} in a
 * {@link RecordStreamToBlockAssertionAdapter} and delegates to the block stream path.
 */
public class StreamModeAwareAssertion extends AbstractEventualStreamAssertion {
    private final Function<HapiSpec, RecordStreamAssertion> assertionFactory;
    private final boolean hasPassedIfNothingFailed;
    private final boolean needsBackgroundTraffic;

    @Nullable
    private final Duration timeout;

    @Nullable
    private AbstractEventualStreamAssertion delegate;

    private StreamModeAwareAssertion(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            @Nullable final Duration timeout,
            final boolean needsBackgroundTraffic) {
        super(hasPassedIfNothingFailed);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.hasPassedIfNothingFailed = hasPassedIfNothingFailed;
        this.timeout = timeout;
        this.needsBackgroundTraffic = needsBackgroundTraffic;
    }

    public static StreamModeAwareAssertion streamMustIncludeNoFailures(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertion, final boolean needsBackgroundTraffic) {
        return new StreamModeAwareAssertion(assertion, true, null, needsBackgroundTraffic);
    }

    public static StreamModeAwareAssertion streamMustIncludePass(
            @NonNull final Function<HapiSpec, RecordStreamAssertion> assertion,
            @Nullable final Duration timeout,
            final boolean needsBackgroundTraffic) {
        return new StreamModeAwareAssertion(assertion, false, timeout, needsBackgroundTraffic);
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

    @Override
    public void unsubscribe() {
        if (delegate != null) {
            delegate.unsubscribe();
        }
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        final var streamMode = resolveStreamMode(spec);
        if (streamMode == BLOCKS) {
            final long shard = spec.setup().shard();
            final long realm = spec.setup().realm();
            final Function<HapiSpec, BlockStreamAssertion> blockFactory =
                    s -> new RecordStreamToBlockAssertionAdapter(assertionFactory.apply(s), shard, realm);
            delegate = EventualBlockStreamAssertion.eventuallyAssertingExplicitPass(blockFactory);
        } else {
            final EventualRecordStreamAssertion recordAssertion;
            if (timeout != null) {
                recordAssertion = hasPassedIfNothingFailed
                        ? EventualRecordStreamAssertion.eventuallyAssertingNoFailures(assertionFactory)
                        : EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(assertionFactory, timeout);
            } else {
                recordAssertion = hasPassedIfNothingFailed
                        ? EventualRecordStreamAssertion.eventuallyAssertingNoFailures(assertionFactory)
                        : EventualRecordStreamAssertion.eventuallyAssertingExplicitPass(assertionFactory);
            }
            if (needsBackgroundTraffic) {
                recordAssertion.withBackgroundTraffic();
            }
            delegate = recordAssertion;
        }
        delegate.execFor(spec);
        return false;
    }

    @Override
    protected String assertionDescription() {
        return delegate != null ? delegate.assertionDescription() : "<pending stream mode detection>";
    }

    private static StreamMode resolveStreamMode(@NonNull final HapiSpec spec) {
        try {
            return spec.startupProperties().getStreamMode("blockStream.streamMode");
        } catch (final Exception e) {
            return StreamMode.BOTH;
        }
    }
}
