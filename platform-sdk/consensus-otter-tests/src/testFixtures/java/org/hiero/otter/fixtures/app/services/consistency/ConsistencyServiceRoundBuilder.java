// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.hiero.otter.fixtures.app.OtterTransaction;

/**
 * Builder of a {@link ConsistencyServiceRound}
 */
public class ConsistencyServiceRoundBuilder {

    private final long roundNumber;
    private final long stateChecksum;
    private final List<Long> transactionNonceList = new ArrayList<>();

    /**
     * Constructor for the {@link ConsistencyServiceRoundBuilder} class
     *
     * @param roundNumber the round number
     * @param stateChecksum the state checksum before the transactions of this round are applied
     */
    public ConsistencyServiceRoundBuilder(final long roundNumber, final long stateChecksum) {
        this.roundNumber = roundNumber;
        this.stateChecksum = stateChecksum;
    }

    /**
     * Add a transaction to this round builder
     *
     * @param transaction the transaction to add
     */
    public void addTransaction(@NonNull final OtterTransaction transaction) {
        transactionNonceList.add(transaction.getNonce());
    }

    /**
     * Build a {@link ConsistencyServiceRound} from this builder
     *
     * @return the built {@link ConsistencyServiceRound}
     */
    @NonNull
    public ConsistencyServiceRound build() {
        return new ConsistencyServiceRound(roundNumber, stateChecksum, transactionNonceList);
    }
}
