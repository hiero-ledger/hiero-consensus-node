// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.verification.traceability;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.guaranteedExtantDir;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.triggerAndCloseAtLeastOneFileIfNotInterrupted;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.support.StreamDataListener;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.stream.proto.TransactionSidecarRecord;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assertions;

/**
 * A class that simultaneously,
 * <ol>
 *     <li>Listens for the actual sidecars written at the given location via
 *     the {@link StreamFileAccess#STREAM_FILE_ACCESS} utility; and,</li>
 *     <li>Registers expected sidecars.</li>
 * </ol>
 * When a client has registered all its expectations with a {@link SidecarWatcher}
 * (necessarily after submitting the transactions triggering those sidecars,
 * since it must look up the consensus timestamp of the expected sidecars), it
 * should call the {@link SidecarWatcher#assertExpectations(HapiSpec)} method.
 *
 * <p>This method throws if any actual sidecar matched the consensus timestamp
 * of an expected sidecar, but did not match other fields; or if there are
 * expected sidecars that were never seen in the actual sidecar stream.
 */
// string literals should not be duplicated
@SuppressWarnings("java:S1192")
public class SidecarWatcher {
    /**
     * The watcher is event-driven (sidecars arrive after a sidecar marker file is noticed).
     * In CI, marker creation/visibility can lag just enough that immediate unsubscribe can race
     * and leave expectations "pending" even though the sidecar file exists on disk.
     */
    private static final long EXPECTATIONS_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final int FINAL_DRAIN_ATTEMPTS = 3;

    private final Path streamFilesPath;
    private final Runnable unsubscribe;
    private final Queue<ExpectedSidecar> expectedSidecars = new LinkedBlockingDeque<>();
    private final Queue<TransactionSidecarRecord> actualSidecars = new LinkedBlockingDeque<>();
    private final HashSet<TransactionSidecarRecord> seenActualSidecars = new HashSet<>();
    // LinkedHashMap lets us easily print mismatches _in the order added_. Important if the
    // records get out-of-sync at one particular test, then all the _rest_ of the tests fail
    // too: It's good to know the _first_ test which fails.
    private final LinkedHashMap<String, List<MismatchedSidecar>> failedSidecars = new LinkedHashMap<>();

    private boolean hasSeenFirstExpectedSidecar = false;

    private record ConstructionDetails(String creatingThread, String stackTrace) {}

    public SidecarWatcher(@NonNull final Path path) {
        this.streamFilesPath = guaranteedExtantDir(path);
        this.unsubscribe = STREAM_FILE_ACCESS.subscribe(streamFilesPath, new StreamDataListener() {
            @Override
            public void onNewSidecar(@NonNull final TransactionSidecarRecord sidecar) {
                enqueueActualSidecar(sidecar);
            }
        });
    }

    public static String stackTrace(Throwable t) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream p = new PrintStream(bos, true, US_ASCII);
        t.printStackTrace(p);
        return bos.toString(US_ASCII);
    }

    /**
     * Adds a new expected sidecar to the queue.
     *
     * @param newExpectedSidecar the new expected sidecar
     */
    public void addExpectedSidecar(@NonNull final ExpectedSidecar newExpectedSidecar) {
        this.expectedSidecars.add(newExpectedSidecar);
    }

    /**
     * Ensures that the sidecar watcher is unsubscribed.
     */
    public void ensureUnsubscribed() {
        unsubscribe.run();
    }

    /**
     * Asserts that there are no mismatched sidecars and no pending sidecars in the
     * context of the given spec.
     *
     * @param spec the spec to assert within
     * @throws AssertionError if there are mismatched sidecars or pending sidecars
     */
    public void assertExpectations(@NonNull final HapiSpec spec) {
        // Ensure our listener has more than fair opportunity to observe all expected sidecars
        triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
        triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
        // Drain sidecars until we either satisfy all expectations, or a bounded timeout elapses.
        // We also synchronously scan sidecar files from disk to avoid relying only on asynchronous
        // file monitor callbacks, which can lag under debugger pauses or slow CI.
        final long deadlineNanos = System.nanoTime() + EXPECTATIONS_TIMEOUT_NANOS;
        while (!expectedSidecars.isEmpty() && System.nanoTime() < deadlineNanos) {
            final var drainedAtLeastOne = drainActualSidecars() || drainUnseenSidecarsFromDisk();
            if (expectedSidecars.isEmpty()) {
                break;
            }
            // If nothing new arrived yet, actively force another record/sidecar file closure cycle.
            if (!drainedAtLeastOne) {
                triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
            }
        }

        // If still pending, perform a few final forced close+drain attempts before failing.
        for (int i = 0; i < FINAL_DRAIN_ATTEMPTS && !expectedSidecars.isEmpty(); i++) {
            triggerAndCloseAtLeastOneFileIfNotInterrupted(spec);
            drainUnseenSidecarsFromDisk();
            drainActualSidecars();
        }

        // Stop listening for any more actual sidecars, then drain anything already queued.
        unsubscribe.run();
        drainUnseenSidecarsFromDisk();
        drainActualSidecars();

        assertTrue(thereAreNoMismatchedSidecars(), getMismatchErrors());
        assertTrue(
                thereAreNoPendingSidecars(),
                "There are some sidecars that have not been yet"
                        + " externalized in the sidecar files after all"
                        + " specs: " + getPendingErrors());
    }

    private boolean drainActualSidecars() {
        boolean drainedAtLeastOne = false;
        TransactionSidecarRecord actualSidecar;
        while ((actualSidecar = actualSidecars.poll()) != null) {
            drainedAtLeastOne = true;
            System.out.println("Observed sidecar: " + actualSidecar);
            final var expectedAtHead = expectedSidecars.peek();
            final boolean matchesConsensusTimestamp = expectedAtHead != null
                    && expectedAtHead.expectedSidecarRecord().matchesConsensusTimestampOf(actualSidecar);
            if (hasSeenFirstExpectedSidecar && matchesConsensusTimestamp) {
                assertIncomingSidecar(actualSidecar);
            } else {
                // sidecar records from different suites can be present in the sidecar
                // files before our first expected sidecar so skip sidecars until we reach
                // the first expected one in the queue
                if (expectedSidecars.isEmpty()) {
                    continue;
                }
                if (matchesConsensusTimestamp) {
                    hasSeenFirstExpectedSidecar = true;
                    assertIncomingSidecar(actualSidecar);
                }
            }
        }
        return drainedAtLeastOne;
    }

    private void enqueueActualSidecar(@NonNull final TransactionSidecarRecord sidecar) {
        synchronized (seenActualSidecars) {
            if (seenActualSidecars.add(sidecar)) {
                actualSidecars.add(sidecar);
            }
        }
    }

    private boolean drainUnseenSidecarsFromDisk() {
        try {
            final var streamData = STREAM_FILE_ACCESS.readStreamDataFrom(streamFilesPath.toString(), "sidecar");
            int added = 0;
            for (final var recordWithSidecars : streamData.records()) {
                for (final var sidecarFile : recordWithSidecars.sidecarFiles()) {
                    for (final var sidecar : sidecarFile.getSidecarRecordsList()) {
                        synchronized (seenActualSidecars) {
                            if (seenActualSidecars.add(sidecar)) {
                                actualSidecars.add(sidecar);
                                added++;
                            }
                        }
                    }
                }
            }
            return added > 0;
        } catch (final UncheckedIOException | IllegalArgumentException e) {
            // Sidecar files may still be in-flight while this assertion loop is polling.
            return false;
        } catch (final Exception e) {
            return false;
        }
    }

    private void assertIncomingSidecar(final TransactionSidecarRecord actualSidecarRecord) {
        // there should always be an expected sidecar at this point,
        // if the queue is empty here, the specs have missed a sidecar
        // and must be updated to account for it
        if (expectedSidecars.isEmpty()) {
            Assertions.fail("No expected sidecar found for incoming sidecar: %s".formatted(actualSidecarRecord));
        }
        final var expectedSidecar = expectedSidecars.poll();
        final var expectedSidecarRecord = expectedSidecar.expectedSidecarRecord();

        if (!expectedSidecar.expectedSidecarRecord().matches(actualSidecarRecord)) {
            final var spec = expectedSidecar.spec();
            failedSidecars.computeIfAbsent(spec, k -> new ArrayList<>());
            failedSidecars.get(spec).add(new MismatchedSidecar(expectedSidecarRecord, actualSidecarRecord));
        }
    }

    private boolean thereAreNoMismatchedSidecars() {
        return failedSidecars.isEmpty();
    }

    private String getMismatchErrors() {
        return getMismatchErrors(pair -> true);
    }

    private String getMismatchErrors(Predicate<MismatchedSidecar> filter) {
        final var messageBuilder = new StringBuilder();
        messageBuilder.append("Mismatch(es) between actual/expected sidecars present: ");
        for (final var kv : failedSidecars.entrySet()) {
            final var faultySidecars = kv.getValue().stream().filter(filter).toList();
            messageBuilder
                    .append("\n\n")
                    .append(faultySidecars.size())
                    .append(" SIDECAR MISMATCH(ES) in SPEC {")
                    .append(kv.getKey())
                    .append("}:");
            int i = 1;
            for (final var pair : faultySidecars) {
                messageBuilder
                        .append("\n******FAILURE #")
                        .append(i++)
                        .append("******\n")
                        .append("***Expected sidecar***\n")
                        .append(pair.expectedSidecarRecordMatcher().toSidecarRecord())
                        .append("***Actual sidecar***\n")
                        .append(pair.actualSidecarRecord());
            }
        }
        return messageBuilder.toString();
    }

    private boolean thereAreNoPendingSidecars() {
        return expectedSidecars.isEmpty();
    }

    private String getPendingErrors() {
        final var messageBuilder = new StringBuilder();
        messageBuilder.append("Pending sidecars not yet seen: ");
        int i = 1;
        for (final var pendingSidecar : expectedSidecars) {
            messageBuilder
                    .append("\n****** PENDING #")
                    .append(i++)
                    .append("******\n")
                    .append("*** Pending sidecar***\n")
                    .append(pendingSidecar.spec())
                    .append(": ")
                    .append(pendingSidecar.expectedSidecarRecord());
        }
        return messageBuilder.toString();
    }
}
