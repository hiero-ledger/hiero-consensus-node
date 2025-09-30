// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static org.hiero.base.utility.ByteUtils.byteArrayToLong;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.hashgraph.Round;

/**
 * Represents a round in the Consistency Service
 *
 * @param roundNumber The number of the round
 * @param currentStateChecksum The state checksum after handling the round
 * @param transactionNonceList A list of transaction nonce values which were included in the round
 */
public record ConsistencyServiceRound(
        long roundNumber, long currentStateChecksum, @NonNull List<Long> transactionNonceList)
        implements Comparable<ConsistencyServiceRound> {

    private static final String ROUND_NUMBER_STRING = "Round Number: ";
    private static final String CURRENT_STATE_STRING = "Current State Checksum: ";
    private static final String TRANSACTION_NONCES_STRING = "Transaction Nonces: ";
    private static final String FIELD_SEPARATOR = "; ";
    private static final String LIST_ELEMENT_SEPARATOR = ", ";

    /**
     * Construct a {@link ConsistencyServiceRound} from a {@link Round}
     *
     * @param round the round to convert
     * @param currentState the long state value of the application after the round has been applied
     * @return the input round, converted to a {@link ConsistencyServiceRound}
     */
    @NonNull
    public static ConsistencyServiceRound fromRound(final @NonNull Round round, final long currentStateChecksum) {
        Objects.requireNonNull(round);

        final List<Long> transactionNonceList = new ArrayList<>();

        round.forEachTransaction(transaction -> {
            transactionNonceList.add(
                    byteArrayToLong(transaction.getApplicationTransaction().toByteArray(), 0));
        });

        return new ConsistencyServiceRound(round.getRoundNum(), currentStateChecksum, transactionNonceList);
    }

    /**
     * Construct a {@link ConsistencyServiceRound} from a string representation
     *
     * @param roundString the string representation of the round
     * @return the new {@link ConsistencyServiceRound}, or null if parsing failed
     */
    @Nullable
    public static ConsistencyServiceRound fromString(final @NonNull String roundString) {
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

            return new ConsistencyServiceRound(roundNumber, currentState, transactionsContents);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final ConsistencyServiceRound other) {
        Objects.requireNonNull(other);

        return Long.compare(this.roundNumber, other.roundNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable final Object other) {
        if (other == null) {
            return false;
        }

        if (other == this) {
            return true;
        }

        if (other
                instanceof
                ConsistencyServiceRound(final long number, final long stateChecksum, final List<Long> nonces)) {
            return roundNumber == number
                    && currentStateChecksum == stateChecksum
                    && transactionNonceList.equals(nonces);
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(roundNumber, currentStateChecksum, transactionNonceList);
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
        builder.append(currentStateChecksum);
        builder.append(FIELD_SEPARATOR);

        builder.append(TRANSACTION_NONCES_STRING);
        builder.append("[");
        for (int index = 0; index < transactionNonceList.size(); index++) {
            builder.append(transactionNonceList.get(index));
            if (index != transactionNonceList.size() - 1) {
                builder.append(LIST_ELEMENT_SEPARATOR);
            }
        }
        builder.append("]\n");

        return builder.toString();
    }
}
