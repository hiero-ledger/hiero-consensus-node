// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a round in the Consistency Service
 *
 * @param roundNumber The number of the round
 * @param currentStateChecksum The state checksum after handling the round
 * @param transactionNonceList A list of transaction nonce values which were included in the round
 */
public record ConsistencyServiceRound(
        long roundNumber, long currentStateChecksum, @NonNull List<Long> transactionNonceList) {

    private static final String ROUND_NUMBER_STRING = "Round Number: ";
    private static final String CURRENT_STATE_STRING = "Current State Checksum: ";
    private static final String TRANSACTION_NONCES_STRING = "Transaction Nonces: ";
    private static final String FIELD_SEPARATOR = "; ";
    private static final String LIST_ELEMENT_SEPARATOR = ", ";

    /**
     * Constructor for the {@link ConsistencyServiceRound} record
     *
     * @param roundNumber the round number
     * @param currentStateChecksum the current state checksum
     * @param transactionNonceList the list of transaction nonce values
     */
    public ConsistencyServiceRound(
            final long roundNumber, final long currentStateChecksum, @NonNull final List<Long> transactionNonceList) {
        this.roundNumber = roundNumber;
        this.currentStateChecksum = currentStateChecksum;
        this.transactionNonceList = new ArrayList<>(transactionNonceList);
    }

    /**
     * Construct a {@link ConsistencyServiceRound} from a string representation
     *
     * @param roundString the string representation of the round
     * @return the new {@link ConsistencyServiceRound}, or null if parsing failed
     */
    @NonNull
    public static ConsistencyServiceRound fromCSVString(final @NonNull String roundString) {
        Objects.requireNonNull(roundString);

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
    }

    /**
     * Produces a string representation of the object that can be parsed by {@link #fromCSVString}.
     * <p>
     * Take care if modifying this method to mirror the change in {@link #fromCSVString}
     *
     * @return a string representation of the object
     */
    @NonNull
    public String toCSVString() {
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
