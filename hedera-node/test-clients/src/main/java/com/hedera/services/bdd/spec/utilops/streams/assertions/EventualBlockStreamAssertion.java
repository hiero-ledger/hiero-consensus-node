// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.BlockSourceFactory;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Function;

public class EventualBlockStreamAssertion extends AbstractEventualStreamAssertion {
    /**
     * The factory for the assertion to be tested.
     */
    private final Function<HapiSpec, BlockStreamAssertion> assertionFactory;

    private final boolean replayExistingFiles;
    private boolean needsBackgroundTraffic = false;

    /**
     * Once this op is submitted, the assertion to be tested.
     */
    @Nullable
    private BlockStreamAssertion assertion;

    /**
     * Returns an {@link EventualBlockStreamAssertion} that will pass as long as the given assertion does not
     * throw an {@link AssertionError} before its timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual block stream assertion that must not fail
     */
    public static EventualBlockStreamAssertion eventuallyAssertingNoFailures(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory) {
        return new EventualBlockStreamAssertion(assertionFactory, true, false);
    }

    /**
     * Returns an {@link EventualBlockStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the default timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual block stream assertion that must pass
     */
    public static EventualBlockStreamAssertion eventuallyAssertingExplicitPass(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory) {
        return new EventualBlockStreamAssertion(assertionFactory, false, false);
    }

    /**
     * Returns an {@link EventualBlockStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the given timeout.
     */
    public static EventualBlockStreamAssertion eventuallyAssertingExplicitPass(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory, @NonNull final Duration timeout) {
        return new EventualBlockStreamAssertion(assertionFactory, false, timeout, false);
    }

    /**
     * Returns an {@link EventualBlockStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the given timeout, after first replaying any existing block files. Mirrors the
     * record-stream variant for genesis-time tests that need to see items emitted at network startup.
     */
    public static EventualBlockStreamAssertion eventuallyAssertingExplicitPassWithReplay(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory, @NonNull final Duration timeout) {
        return new EventualBlockStreamAssertion(assertionFactory, false, timeout, true).withBackgroundTraffic();
    }

    private EventualBlockStreamAssertion(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            final boolean replayExistingFiles) {
        super(hasPassedIfNothingFailed);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.replayExistingFiles = replayExistingFiles;
    }

    private EventualBlockStreamAssertion(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed,
            @NonNull final Duration timeout,
            final boolean replayExistingFiles) {
        super(hasPassedIfNothingFailed, timeout);
        this.assertionFactory = requireNonNull(assertionFactory);
        this.replayExistingFiles = replayExistingFiles;
    }

    @Override
    public boolean needsBackgroundTraffic() {
        return needsBackgroundTraffic;
    }

    /** Opts this assertion into background traffic so blocks keep closing while it waits. */
    public EventualBlockStreamAssertion withBackgroundTraffic() {
        this.needsBackgroundTraffic = true;
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        assertion = requireNonNull(assertionFactory.apply(spec));
        unsubscribe = BlockSourceFactory.blockSourceFor(spec).subscribe(new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return replayExistingFiles;
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                requireNonNull(block);
                try {
                    if (assertion.test(block)) {
                        result.pass();
                    }
                } catch (final AssertionError e) {
                    result.fail(e.getMessage());
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
}
