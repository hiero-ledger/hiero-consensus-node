// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
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

    /** The spec this assertion was submitted for, retained for the final timeout rescan. */
    @Nullable
    private HapiSpec spec;

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
        this.spec = spec;
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

    /**
     * Deterministic final rescan for the block-node ({@code writerMode=GRPC}) path. The live
     * subscription matches items as blocks arrive, but an item observed before its {@code .via}
     * transaction id was registered is buffered and only re-checked when another record arrives; if
     * the stream goes idle first, the assertion can time out even though the item is present. Re-read
     * the whole stream once through a fresh assertion instance with the now fully-populated spec
     * registry so such a spurious timeout is corrected. A genuine miss still fails. Mirrors the
     * one-shot {@code allBlocks()} drain used by {@code SidecarWatcher}. No-op when no block node is
     * active (e.g. {@code writerMode=FILE}), leaving the original timeout to stand.
     */
    @Override
    protected boolean recoveredAfterTimeout() {
        if (spec == null) {
            return false;
        }
        return BlockNodeReader.forActiveNetwork()
                .map(reader -> {
                    final var rescan = assertionFactory.apply(spec);
                    try {
                        for (final var block : reader.allBlocks()) {
                            if (rescan.test(block)) {
                                return true;
                            }
                        }
                    } catch (final AssertionError ignore) {
                        // A failure during rescan is not a spurious timeout; let the original result stand.
                        return false;
                    }
                    return false;
                })
                .orElse(false);
    }

    @Override
    protected String assertionDescription() {
        return assertion == null ? "<N/A>" : assertion.toString();
    }
}
