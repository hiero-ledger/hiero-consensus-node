// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludePassWithReplayFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.utilops.streams.assertions.BlockStreamAssertion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Asserts the synthetic {@code LedgerIdPublication} is externalized at a consensus time strictly after every
 * transaction already streamed in its block.
 *
 * <p>{@code HandleWorkflow.handleRound()} snapshots the last-used consensus time <i>before</i> {@code handleEvents()}
 * runs the round, then reuses that snapshot for the publication dispatch. Since the ceremony is finished by a
 * {@code HistoryProofVote} handled inside {@code handleEvents()}, the publication is stamped with a time that
 * precedes transactions already written to the block; the same snapshot (plus the same 2ns offset) is also handed to
 * {@code NodeFeeManager.distributeFees()}, so in a staking-boundary round the two can share an instant.
 *
 * <p>Runs on the shared network, which publishes a ledger id during its genesis TSS ceremony since
 * {@code tss.hintsEnabled} and {@code tss.historyEnabled} both default to true; the assertion replays existing
 * block files to see it.
 */
public class LedgerIdPublicationTimestampTest {
    private static final Duration PUBLICATION_TIMEOUT = Duration.ofMinutes(5);

    @HapiTest
    final Stream<DynamicTest> ledgerIdPublicationIsNotBackdatedWithinItsBlock() {
        return hapiTest(
                streamMustIncludePassWithReplayFrom(
                        spec -> new LedgerIdPublicationOrderAssertion(), PUBLICATION_TIMEOUT),
                // Some traffic, in case the network is still finishing its genesis ceremony
                cryptoCreate("a"));
    }

    /**
     * Passes as soon as a {@code LedgerIdPublication} result is seen at a consensus time strictly after the latest
     * transaction result preceding it in the same block; fails if it is at or before that time.
     */
    private static class LedgerIdPublicationOrderAssertion implements BlockStreamAssertion {
        @Override
        public boolean test(@NonNull final Block block) throws AssertionError {
            requireNonNull(block);
            Timestamp latestSoFar = null;
            boolean nextResultIsPublication = false;
            for (final var item : block.items()) {
                switch (item.item().kind()) {
                    case SIGNED_TRANSACTION ->
                        nextResultIsPublication = isLedgerIdPublication(item.signedTransactionOrThrow());
                    case TRANSACTION_RESULT -> {
                        final var at = item.transactionResultOrThrow().consensusTimestampOrThrow();
                        if (nextResultIsPublication) {
                            assertStrictlyAfter(at, latestSoFar);
                            return true;
                        }
                        latestSoFar = latest(latestSoFar, at);
                    }
                    default -> {
                        // Only transaction inputs and results carry the times under test
                    }
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "LedgerIdPublicationOrder";
        }
    }

    private static void assertStrictlyAfter(
            @NonNull final Timestamp publishedAt, @Nullable final Timestamp latestSoFar) {
        if (latestSoFar == null) {
            throw new AssertionError("LedgerIdPublication was the first transaction result in its block, so its "
                    + "ordering relative to the round's transactions could not be checked");
        }
        final var published = asInstant(publishedAt);
        final var latest = asInstant(latestSoFar);
        if (!published.isAfter(latest)) {
            throw new AssertionError(String.format(
                    "LedgerIdPublication externalized at %s, but the block already contained a transaction result at "
                            + "%s (%s by %dns) - consensus times must be strictly increasing in the stream",
                    published,
                    latest,
                    published.equals(latest) ? "duplicated" : "backdated",
                    Duration.between(published, latest).toNanos()));
        }
    }

    private static boolean isLedgerIdPublication(@NonNull final Bytes signedTransaction) {
        try {
            return TransactionParts.from(signedTransaction).body().hasLedgerIdPublication();
        } catch (RuntimeException ignore) {
            // Not every signed transaction item is a HAPI transaction body (e.g. state signatures)
            return false;
        }
    }

    private static Timestamp latest(@Nullable final Timestamp a, @NonNull final Timestamp b) {
        return (a == null || asInstant(b).isAfter(asInstant(a))) ? b : a;
    }

    private static Instant asInstant(@NonNull final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }
}
