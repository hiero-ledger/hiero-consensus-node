// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.function.Function;

public class EventualBlockStreamAssertion extends AbstractEventualStreamAssertion {
    /**
     * The factory for the assertion to be tested.
     */
    private final Function<HapiSpec, BlockStreamAssertion> assertionFactory;
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
        return new EventualBlockStreamAssertion(assertionFactory, true);
    }

    /**
     * Returns an {@link EventualBlockStreamAssertion} that will pass only if the given assertion explicitly
     * passes within the default timeout.
     * @param assertionFactory the assertion factory
     * @return the eventual block stream assertion that must pass
     */
    public static EventualBlockStreamAssertion eventuallyAssertingExplicitPass(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory) {
        return new EventualBlockStreamAssertion(assertionFactory, false);
    }

    private EventualBlockStreamAssertion(
            @NonNull final Function<HapiSpec, BlockStreamAssertion> assertionFactory,
            final boolean hasPassedIfNothingFailed) {
        super(hasPassedIfNothingFailed);
        this.assertionFactory = requireNonNull(assertionFactory);
    }

    @Override
    public boolean needsBackgroundTraffic() {
        return false;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        assertion = requireNonNull(assertionFactory.apply(spec));
        unsubscribe = STREAM_FILE_ACCESS.subscribe(blockStreamLocFor(spec), new StreamDataListener() {
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

    /**
     * Returns the block stream location for the first listed node in the network targeted
     * by the given spec.
     *
     * @param spec the spec
     * @return a record stream location for the first listed node in the network
     */
    private static Path blockStreamLocFor(@NonNull final HapiSpec spec) {
        return spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR);
    }
}
