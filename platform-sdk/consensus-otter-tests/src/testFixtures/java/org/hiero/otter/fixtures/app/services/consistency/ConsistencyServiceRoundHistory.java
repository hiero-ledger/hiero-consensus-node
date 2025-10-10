// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.otter.fixtures.app.OtterTransaction;

/**
 * Object representing the entire history of rounds handled by the Consistency Service.
 * <p>
 * Contains a record of all rounds that have come to consensus and the transactions which were included.
 * <p>
 * Writes a CSV file containing the history of transaction handling, so that any replayed transactions after a reboot
 * can be confirmed to match the original handling.
 * <p>
 * Note: Partially written log lines are simply ignored by this tool, so it is NOT verifying handling of transactions in
 * a partially handled round at the time of a crash.
 */
public class ConsistencyServiceRoundHistory implements Closeable {
    private static final Logger log = LogManager.getLogger(ConsistencyServiceRoundHistory.class);

    /**
     * A map from round number to historical rounds
     */
    private final Map<Long, ConsistencyServiceRound> roundHistory;

    /**
     * A set of all transaction nonce values which have been seen
     */
    private final Set<Long> seenNonceValues;

    /**
     * The location of the log file
     */
    private Path logFilePath;

    /**
     * The writer for the log file
     */
    private BufferedWriter writer;

    @Nullable
    private ConsistencyServiceRoundBuilder roundBuilder;

    /**
     * Constructor
     */
    public ConsistencyServiceRoundHistory() {
        this.roundHistory = new HashMap<>();
        this.seenNonceValues = new HashSet<>();
    }

    /**
     * Initializer
     * <p>
     * Reads the contents of the log file if it exists, and adds the included rounds to the history
     *
     * @param logFilePath the location of the log file
     */
    public void init(@NonNull final Path logFilePath) {
        this.logFilePath = requireNonNull(logFilePath);

        log.info(STARTUP.getMarker(), "Consistency testing tool log path: {}", logFilePath);

        tryReadLog();

        try {
            this.writer = new BufferedWriter(new FileWriter(logFilePath.toFile(), true));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to open writer for transaction handling history", e);
        }
    }

    /**
     * Reads the contents of the log file if it exists, and adds the included rounds to the history
     */
    private void tryReadLog() {
        if (!Files.exists(logFilePath)) {
            log.info(STARTUP.getMarker(), "No log file found. Starting without any previous history");
            return;
        }

        log.info(STARTUP.getMarker(), "Log file found. Parsing previous history");

        try (final FileReader in = new FileReader(logFilePath.toFile());
                final BufferedReader reader = new BufferedReader(in)) {
            reader.lines().forEach(line -> {
                final ConsistencyServiceRound parsedRound = ConsistencyServiceRound.fromCSVString(line);

                if (parsedRound == null) {
                    log.warn(STARTUP.getMarker(), "Failed to parse line from log file: {}", line);
                    return;
                }

                addRoundToHistory(parsedRound);
            });
        } catch (final IOException e) {
            log.error(EXCEPTION.getMarker(), "Failed to read log file", e);
        }
    }

    /**
     * Add a transaction nonce to the list of seen nonce values
     *
     * @param nonce the transaction nonce to add
     */
    private void addNonce(final long nonce) {
        if (!seenNonceValues.add(nonce)) {
            final String error = "Transaction with nonce `" + nonce + "` has already been applied to the state";

            log.error(EXCEPTION.getMarker(), error);
        }
    }

    /**
     * Compare a newly received round with the historical counterpart. Logs an error if the new round isn't identical to
     * the historical round
     *
     * @param newRound the round that is being newly processed
     * @param historicalRound the historical round that the new round is being compared to
     */
    private void compareWithHistoricalRound(
            @NonNull final ConsistencyServiceRound newRound, @NonNull final ConsistencyServiceRound historicalRound) {

        requireNonNull(newRound);
        requireNonNull(historicalRound);

        if (!newRound.equals(historicalRound)) {
            final String error = "Round " + newRound.roundNumber() + " with transaction nonce values "
                    + newRound.transactionNonceList()
                    + " doesn't match historical counterpart with transactions "
                    + historicalRound.transactionNonceList();

            log.error(EXCEPTION.getMarker(), error);
        }
    }

    /**
     * Add a round to the history. Errors are logged.
     *
     * @param newRound the round to add
     */
    private void addRoundToHistory(@NonNull final ConsistencyServiceRound newRound) {
        roundHistory.put(newRound.roundNumber(), newRound);
        newRound.transactionNonceList().forEach(this::addNonce);
    }

    /**
     * Writes the given round to the log file
     *
     * @param round the round to write to the log file
     */
    private void writeRoundToLog(final @NonNull ConsistencyServiceRound round) {
        requireNonNull(round);

        try {
            writer.write(round.toCSVString());
            writer.flush();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write round `%s` to log".formatted(round.roundNumber()), e);
        }
    }

    /**
     * Setup a new builder for the given round
     *
     * @param round the round to process
     * @param stateChecksum the checksum of the state at the beginning of the round
     */
    public void onRoundStart(@NonNull final Round round, final long stateChecksum) {
        requireNonNull(round);
        roundBuilder = new ConsistencyServiceRoundBuilder(round.getRoundNum(), stateChecksum);
    }

    /**
     * Adds the transaction to the builder for the current round
     *
     * @param transaction the transaction to process
     */
    public void onTransaction(@NonNull final OtterTransaction transaction) {
        assert roundBuilder != null;
        roundBuilder.addTransaction(transaction);
    }

    /**
     * Finalize the current round
     * <p>
     * If the input round already exists in the history, this method checks that all transactions are identical to the
     * corresponding historical round
     * <p>
     * If the input round doesn't already exist in the history, this method adds it to the history
     */
    public void onRoundComplete() {
        assert roundBuilder != null;
        final ConsistencyServiceRound currentRound = roundBuilder.build();
        final ConsistencyServiceRound historicalRound = roundHistory.get(currentRound.roundNumber());

        if (historicalRound == null) {
            // round doesn't already appear in the history, so record it
            addRoundToHistory(currentRound);
            writeRoundToLog(currentRound);
        } else {
            // if we found a round with the same round number in the round history, make sure the rounds are identical
            compareWithHistoricalRound(currentRound, historicalRound);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void close() {
        try {
            writer.close();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to close writer for transaction handling history", e);
        }
    }
}
