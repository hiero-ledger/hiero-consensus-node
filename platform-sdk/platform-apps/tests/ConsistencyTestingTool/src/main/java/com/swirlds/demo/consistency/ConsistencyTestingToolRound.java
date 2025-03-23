// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.common.utility.ByteUtils.byteArrayToLong;
import static com.swirlds.demo.consistency.ConsistencyTestingToolState.isSystemTransaction;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * Represents a round in the ConsistencyTestingTool
 *
 * @param roundNumber          The number of the round
 * @param currentState         The app state after handling the round
 * @param transactionsContents A list of transactions which were included in the round
 */
public record ConsistencyTestingToolRound(long roundNumber, long currentState, @NonNull List<Long> transactionsContents)
        implements Comparable<ConsistencyTestingToolRound> {

    private static final Logger logger = LogManager.getLogger(ConsistencyTestingToolRound.class);

    private static final String ROUND_NUMBER_STRING = "Round Number: ";
    private static final String CURRENT_STATE_STRING = "Current State: ";
    private static final String TRANSACTIONS_STRING = "Transactions: ";
    private static final String FIELD_SEPARATOR = "; ";
    private static final String LIST_ELEMENT_SEPARATOR = ", ";

    /**
     * Construct a {@link ConsistencyTestingToolRound} from a {@link Round}
     *
     * @param round        the round to convert
     * @param currentState the long state value of the application after the round has been applied
     * @return the input round, converted to a {@link ConsistencyTestingToolRound}
     */
    @NonNull
    public static ConsistencyTestingToolRound fromRound(final @NonNull Round round, final long currentState) {
        Objects.requireNonNull(round);

        final List<Long> transactionContents = new ArrayList<>();

        round.forEachTransaction(transaction -> {
            if (isSystemTransaction(transaction)) {
                return;
            }
            transactionContents.add(
                    byteArrayToLong(transaction.getApplicationTransaction().toByteArray(), 0));
        });

        return new ConsistencyTestingToolRound(round.getRoundNum(), currentState, transactionContents);
    }

    /**
     * Construct a {@link ConsistencyTestingToolRound} from a string representation
     *
     * @param roundString the string representation of the round
     * @return the new {@link ConsistencyTestingToolRound}, or null if parsing failed
     */
    @Nullable
    public static ConsistencyTestingToolRound fromString(final @NonNull String roundString) {
        Objects.requireNonNull(roundString);

        try {
            final List<String> fields =
                    Arrays.stream(roundString.split(FIELD_SEPARATOR)).toList();

            String field = fields.get(0);
            final long roundNumber = Long.parseLong(field.substring(ROUND_NUMBER_STRING.length()));

            field = fields.get(1);
            final long currentState = Long.parseLong(field.substring(CURRENT_STATE_STRING.length()));

            field = fields.get(2);
            final String transactionsString = field.substring(field.indexOf("[") + 1, field.indexOf("]"));
            final List<Long> transactionsContents = Arrays.stream(transactionsString.split(LIST_ELEMENT_SEPARATOR))
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();

            return new ConsistencyTestingToolRound(roundNumber, currentState, transactionsContents);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull ConsistencyTestingToolRound other) {
        Objects.requireNonNull(other);

        return Long.compare(this.roundNumber, other.roundNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final @Nullable Object other) {
        if (other == null) {
            return false;
        }

        if (other == this) {
            return true;
        }

        if (other instanceof final ConsistencyTestingToolRound otherRound) {
            return roundNumber == otherRound.roundNumber
                    && currentState == otherRound.currentState
                    && transactionsContents.equals(otherRound.transactionsContents);
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(roundNumber, currentState, transactionsContents);
    }

    /**
     * Produces a string representation of the object that can be parsed by {@link #fromString}.
     * <p>
     * Take care if modifying this method to mirror the change in {@link #fromString}
     *
     * @return a string representation of the object
     */
    @Override
    @NonNull
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        builder.append(ROUND_NUMBER_STRING);
        builder.append(roundNumber);
        builder.append(FIELD_SEPARATOR);

        builder.append(CURRENT_STATE_STRING);
        builder.append(currentState);
        builder.append(FIELD_SEPARATOR);

        builder.append(TRANSACTIONS_STRING);
        builder.append("[");
        for (int index = 0; index < transactionsContents.size(); index++) {
            builder.append(transactionsContents.get(index));
            if (index != transactionsContents.size() - 1) {
                builder.append(LIST_ELEMENT_SEPARATOR);
            }
        }
        builder.append("]\n");

        return builder.toString();
    }
}
