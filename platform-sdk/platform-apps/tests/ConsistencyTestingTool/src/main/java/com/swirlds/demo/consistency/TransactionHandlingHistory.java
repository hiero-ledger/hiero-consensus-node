// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Object representing the entire history of transaction handling for the testing app
 * <p>
 * Contains a record of all rounds that have come to consensus, and the transactions which were included
 * <p>
 * Writes a log file containing the history of transaction handling, so that any replayed transactions after a reboot
 * can be confirmed to match the original handling. NOTE: Partially written log lines are simply ignored by this tool,
 * so it is NOT verifying handling of transactions in a partially handled round at the time of a crash.
 */
public class TransactionHandlingHistory implements Closeable {
    private static final Logger logger = LogManager.getLogger(TransactionHandlingHistory.class);

    /**
     * A map from round number to historical rounds
     */
    private final Map<Long, ConsistencyTestingToolRound> roundHistory;

    /**
     * A set of all transactions which have been seen
     */
    private final Set<Long> seenTransactions;

    /**
     * The location of the log file
     */
    private Path logFilePath;

    /**
     * The writer for the log file
     */
    private BufferedWriter writer;

    /**
     * The round number of the previous round handled
     */
    private long previousRoundHandled;

    /**
     * Constructor
     */
    public TransactionHandlingHistory() {
        this.roundHistory = new HashMap<>();
        this.seenTransactions = new HashSet<>();

        // initialization is happening in init()
    }

    /**
     * Initializer
     * <p>
     * Reads the contents of the log file if it exists, and adds the included rounds to the history
     *
     * @param logFilePath the location of the log file
     */
    public void init(final @NonNull Path logFilePath) {
        this.logFilePath = Objects.requireNonNull(logFilePath);

        logger.info(STARTUP.getMarker(), "Consistency testing tool log path: {}", logFilePath);

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
            logger.info(STARTUP.getMarker(), "No log file found. Starting without any previous history");
            return;
        }

        logger.info(STARTUP.getMarker(), "Log file found. Parsing previous history");

        try (FileReader in = new FileReader(logFilePath.toFile());
                final BufferedReader reader = new BufferedReader(in)) {
            reader.lines().forEach(line -> {
                final ConsistencyTestingToolRound parsedRound = ConsistencyTestingToolRound.fromString(line);

                if (parsedRound == null) {
                    logger.warn(STARTUP.getMarker(), "Failed to parse line from log file: {}", line);
                    return;
                }

                addRoundToHistory(parsedRound);
            });
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to read log file", e);
        }
    }

    /**
     * Add a transaction to the list of seen transactions
     *
     * @param transaction the transaction to add
     * @return an error message if the transaction has already been seen, otherwise null
     */
    @Nullable
    private String addTransaction(final long transaction) {
        if (!seenTransactions.add(transaction)) {
            final String error =
                    "Transaction with contents `" + transaction + "` has already been applied to the state";

            logger.error(EXCEPTION.getMarker(), error);
            return error;
        }

        return null;
    }

    /**
     * Compare a newly received round with the historical counterpart. Logs an error if the new round isn't identical to
     * the historical round
     *
     * @param newRound        the round that is being newly processed
     * @param historicalRound the historical round that the new round is being compared to
     * @return an error message if the new round doesn't match the historical round, otherwise null
     */
    @Nullable
    private String compareWithHistoricalRound(
            final @NonNull ConsistencyTestingToolRound newRound,
            final @NonNull ConsistencyTestingToolRound historicalRound) {

        Objects.requireNonNull(newRound);
        Objects.requireNonNull(historicalRound);

        if (!newRound.equals(historicalRound)) {
            final String error =
                    "Round " + newRound.roundNumber() + " with transactions " + newRound.transactionsContents()
                            + " doesn't match historical counterpart with transactions "
                            + historicalRound.transactionsContents();

            logger.error(EXCEPTION.getMarker(), error);
            return error;
        }

        return null;
    }

    /**
     * Add a round to the history. Errors are logged.
     *
     * @param newRound the round to add
     * @return a list of errors that occurred while adding the round
     */
    @NonNull
    private List<String> addRoundToHistory(final @NonNull ConsistencyTestingToolRound newRound) {
        final List<String> errors = new ArrayList<>();

        roundHistory.put(newRound.roundNumber(), newRound);

        newRound.transactionsContents().forEach(transaction -> {
            final String error = addTransaction(transaction);

            if (error != null) {
                errors.add(error);
            }
        });

        if (roundHistory.size() <= 1) {
            previousRoundHandled = newRound.roundNumber();
            // only 1 round is in the history, so no additional checks are necessary
            return errors;
        }

        final long newRoundNumber = newRound.roundNumber();

        // make sure round numbers always increase
        if (newRoundNumber <= previousRoundHandled) {
            final String error = "Round " + newRoundNumber + " is not greater than round " + previousRoundHandled;
            logger.error(EXCEPTION.getMarker(), error);

            errors.add(error);
        }

        previousRoundHandled = newRound.roundNumber();

        return errors;
    }

    /**
     * Writes the given round to the log file
     *
     * @param round the round to write to the log file
     */
    private void writeRoundToLog(final @NonNull ConsistencyTestingToolRound round) {
        Objects.requireNonNull(round);

        try {
            writer.write(round.toString());
            writer.flush();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write round `%s` to log".formatted(round.roundNumber()), e);
        }
    }

    /**
     * Process a round
     * <p>
     * If the input round already exists in the history, this method checks that all transactions are identical to the
     * corresponding historical round
     * <p>
     * If the input round doesn't already exist in the history, this method adds it to the history
     *
     * @param round the round to process
     * @return a list of errors that occurred while processing the round
     */
    @NonNull
    public List<String> processRound(final @NonNull ConsistencyTestingToolRound round) {
        Objects.requireNonNull(round);

        final ConsistencyTestingToolRound historicalRound = roundHistory.get(round.roundNumber());

        final List<String> errors = new ArrayList<>();
        if (historicalRound == null) {
            // round doesn't already appear in the history, so record it
            errors.addAll(addRoundToHistory(round));
            writeRoundToLog(round);
        } else {
            // if we found a round with the same round number in the round history, make sure the rounds are identical
            final String error = compareWithHistoricalRound(round, historicalRound);

            if (error != null) {
                errors.add(error);
            }
        }

        return errors;
    }

    public void close() {
        try {
            writer.close();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to close writer for transaction handling history", e);
        }
    }
}
