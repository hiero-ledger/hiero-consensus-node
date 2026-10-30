// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.junit.support.translators.BlockTransactionalUnitTranslator;
import com.hedera.services.bdd.junit.support.translators.RoleFreeBlockUnitSplit;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link com.hedera.services.bdd.spec.utilops.UtilOp} that registers itself with {@link
 * StreamFileAccess} and continually updates the {@link RecordStreamAssertion} yielded by a given
 * factory with each new {@link RecordStreamItem}.
 */
public class EventualRecordStreamAssertion extends AbstractEventualStreamAssertion {
    private static final Logger log = LogManager.getLogger(EventualRecordStreamAssertion.class);

    private final RoleFreeBlockUnitSplit blockSplitter = new RoleFreeBlockUnitSplit();

    @Nullable
    private BlockTransactionalUnitTranslator blockTranslator;

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

    // Serialize live validation with the final pending-item check on the spec thread.
    private final Object assertionLock = new Object();
    private boolean assertionFailed;

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
        unsubscribe = STREAM_FILE_ACCESS.subscribe(recordStreamLocFor(spec), listenerFor(spec));
        return false;
    }

    StreamDataListener listenerFor(final HapiSpec spec) {
        assertion = requireNonNull(assertionFactory.apply(spec));
        final long shard = spec.setup().shard();
        final long realm = spec.setup().realm();
        blockTranslator = new BlockTransactionalUnitTranslator(shard, realm);
        return new StreamDataListener() {
            @Override
            public boolean replayExistingFiles() {
                return replayExistingFiles;
            }

            @Override
            public void onNewItem(@NonNull final RecordStreamItem item) {
                requireNonNull(item);
                final boolean passed = evaluate(() -> assertion.isApplicableTo(item) && assertion.test(item));
                stopIfRequested(passed);
            }

            @Override
            public void onNewBlock(@NonNull final Block block) {
                requireNonNull(block);
                try {
                    final var units = blockSplitter.split(block);
                    for (final var unit : units) {
                        final var records = blockTranslator.translate(unit.withBatchTransactionParts());
                        for (final var record : records) {
                            final var item = toRecordStreamItem(record);
                            onNewItem(item);
                        }
                    }
                } catch (final AssertionError e) {
                    result.fail(e.getMessage());
                } catch (final Exception e) {
                    log.warn("Failed to translate block to record stream items", e);
                }
            }

            @Override
            public void onNewSidecar(TransactionSidecarRecord sidecar) {
                evaluate(() -> assertion.isApplicableToSidecar(sidecar) && assertion.testSidecar(sidecar));
            }

            @Override
            public String name() {
                return assertion.toString();
            }
        };
    }

    private boolean evaluate(final BooleanSupplier validation) {
        synchronized (assertionLock) {
            if (isDone() || assertionFailed) {
                return false;
            }
            try {
                if (validation.getAsBoolean()) {
                    result.pass();
                    return true;
                }
            } catch (final AssertionError | RuntimeException e) {
                // A later matching record must not overwrite a genuine failure with a pass.
                assertionFailed = true;
                result.fail(e.toString());
            }
            return false;
        }
    }

    private void stopIfRequested(final boolean passed) {
        // Unsubscription may wait for the stream monitor; never do it while holding assertionLock.
        if (passed && stopAfterFirstSuccess && unsubscribe != null) {
            unsubscribe.run();
        }
    }

    @Override
    public void assertHasPassed() {
        // The spec's operations have finished, so .via() IDs should now be registered. Retry before
        // waiting to avoid paying the full timeout when the matching record arrived early.
        if (assertion != null) {
            evaluate(assertion::recheckPending);
        }
        super.assertHasPassed();
    }

    @Override
    protected boolean recoveredAfterTimeout() {
        synchronized (assertionLock) {
            // Preserve the original assertion and its failures; only retry buffered candidates.
            return assertion != null && !assertionFailed && assertion.recheckPending();
        }
    }

    @Override
    protected String assertionDescription() {
        return assertion == null ? "<N/A>" : assertion.toString();
    }

    @Override
    public String toString() {
        return "EventuallyRecordStream{" + assertionDescription() + "}";
    }

    private static RecordStreamItem toRecordStreamItem(@NonNull final SingleTransactionRecord record) {
        return RecordStreamItem.newBuilder()
                .setTransaction(fromPbj(record.transaction()))
                .setRecord(fromPbj(record.transactionRecord()))
                .build();
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
